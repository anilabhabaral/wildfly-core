/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2012, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.host.controller.mgmt;

import static org.jboss.as.server.security.sasl.Constants.JBOSS_DOMAIN_SERVER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.HOST;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OUTCOME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUCCESS;
import static org.jboss.as.host.controller.logging.HostControllerLogger.ROOT_LOGGER;
import static org.jboss.as.process.protocol.ProtocolUtils.expectHeader;

import java.io.DataInput;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executor;

import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.HashUtil;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.ProxyController;
import org.jboss.as.controller.client.Operation;
import org.jboss.as.controller.client.OperationBuilder;
import org.jboss.as.controller.client.OperationMessageHandler;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.domain.controller.DomainController;
import org.jboss.as.host.controller.ManagedServerOperationsFactory;
import org.jboss.as.host.controller.ServerInventory;
import org.jboss.as.host.controller.logging.HostControllerLogger;
import org.jboss.as.protocol.StreamUtils;
import org.jboss.as.protocol.mgmt.ActiveOperation;
import org.jboss.as.protocol.mgmt.ActiveOperation.ResultHandler;
import org.jboss.as.protocol.mgmt.FlushableDataOutput;
import org.jboss.as.protocol.mgmt.ManagementChannelHandler;
import org.jboss.as.protocol.mgmt.ManagementProtocol;
import org.jboss.as.protocol.mgmt.ManagementProtocolHeader;
import org.jboss.as.protocol.mgmt.ManagementRequestContext;
import org.jboss.as.protocol.mgmt.ManagementRequestHandler;
import org.jboss.as.protocol.mgmt.ManagementRequestHandlerFactory;
import org.jboss.as.protocol.mgmt.ManagementRequestHeader;
import org.jboss.as.protocol.mgmt.ManagementResponseHeader;
import org.jboss.as.protocol.mgmt.ProtocolUtils;
import org.jboss.as.protocol.mgmt.RequestProcessingException;
import org.jboss.as.repository.ContentReference;
import org.jboss.as.repository.DeploymentFileRepository;
import org.jboss.as.repository.RemoteFileRequestAndHandler;
import org.jboss.as.server.mgmt.domain.DomainServerProtocol;
import org.jboss.as.server.mgmt.domain.ServerToHostRemoteFileRequestAndHandler;
import org.jboss.dmr.ModelNode;
import org.jboss.remoting3.Channel;
import org.jboss.remoting3.MessageOutputStream;
import org.wildfly.security.auth.server.SecurityIdentity;

/**
 * Handler responsible for the server to host-controller protocol.
 *
 * @author Emanuel Muckenhuber
 */
public class ServerToHostProtocolHandler implements ManagementRequestHandlerFactory {

    static final Operation EMPTY_OP;
    static {
        ModelNode mn  = new ModelNode();
        mn.get(OP).set("register-server"); // This is actually not used anywhere
        mn.get(OP_ADDR).setEmptyList();
        mn.protect();
        EMPTY_OP = OperationBuilder.create(mn).build();
    }

    private final ServerInventory serverInventory;
    private final OperationExecutor operationExecutor;
    private final DomainController domainController;
    private final ManagementChannelHandler channelHandler;
    private final DeploymentFileRepository deploymentFileRepository;
    private final Executor registrations;
    private final ExpressionResolver expressionResolver;

    private volatile String serverProcessName;

    ServerToHostProtocolHandler(ServerInventory serverInventory, OperationExecutor operationExecutor, DomainController domainController, ManagementChannelHandler channelHandler, Executor registrations,
            ExpressionResolver expressionResolver) {
        this.serverInventory = serverInventory;
        this.operationExecutor = operationExecutor;
        this.domainController = domainController;
        this.channelHandler = channelHandler;
        this.registrations = registrations;
        this.deploymentFileRepository = domainController.getLocalFileRepository();
        this.expressionResolver = expressionResolver;
    }

    @Override
    public ManagementRequestHandler<?, ?> resolveHandler(final RequestHandlerChain handlers, final ManagementRequestHeader header) {
        final byte operationId = header.getOperationId();
        switch (operationId) {
            case DomainServerProtocol.REGISTER_REQUEST:
                handlers.registerActiveOperation(header.getBatchId(), null);
                return  new AuthorizationRequestHandler<>(new ServerRegistrationRequestHandler());
            case DomainServerProtocol.SERVER_RECONNECT_REQUEST:
                handlers.registerActiveOperation(header.getBatchId(), null);
                return new AuthorizationRequestHandler<>(new ServerReconnectRequestHandler());
            case DomainServerProtocol.GET_FILE_REQUEST:
                handlers.registerActiveOperation(header.getBatchId(), null);
                return new AuthorizationRequestHandler<>(new GetFileOperation());
            case DomainServerProtocol.SERVER_STARTED_REQUEST:
                handlers.registerActiveOperation(header.getBatchId(), serverInventory);
                return new AuthorizationRequestHandler<>(new ServerStartedHandler(serverProcessName));
            case DomainServerProtocol.SERVER_INSTABILITY_REQUEST:
                handlers.registerActiveOperation(header.getBatchId(), serverInventory);
                return new AuthorizationRequestHandler<>(new ServerUnstableHandler(serverProcessName));

        }
        return handlers.resolveNext();
    }

    /**
     * wrapper to the DomainController and the underlying {@code ModelController} to execute
     * a {@code OperationStepHandler} implementation directly, bypassing normal domain coordination layer.
     */
    public interface OperationExecutor {

        /**
         * Execute the operation.
         *
         * @param operation operation
         * @param handler the message handler
         * @param control the transaction control
         * @param step the step to be executed
         * @return the result
         */
        ModelNode execute(Operation operation, OperationMessageHandler handler, ModelController.OperationTransactionControl control, OperationStepHandler step);

        /**
         * Join an existing operation
         *
         * @param operation the operation to execute
         * @param handler the message handler
         * @param control the transaction control
         * @param step the step to be executed
         * @param permit the permit
         * @return the result
         */
        ModelNode joinActiveOperation(ModelNode operation, OperationMessageHandler handler, ModelController.OperationTransactionControl control, OperationStepHandler step, int permit);

    }

    class AuthorizationRequestHandler<T, A> implements ManagementRequestHandler<T, A> {

        final ManagementRequestHandler<T, A> delegate;

        AuthorizationRequestHandler(ManagementRequestHandler<T, A> delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handleRequest(DataInput input, ResultHandler<T> resultHandler, ManagementRequestContext<A> context)
                throws IOException {
            SecurityIdentity identity = context.getChannel().getConnection().getLocalIdentity();
            if (!identity.getRoles().contains(JBOSS_DOMAIN_SERVER)) {
                safeWriteResponse(context, ROOT_LOGGER.identityNotAuthorizedAsServer(identity.getPrincipal().getName()));
                return;
            }

            delegate.handleRequest(input, resultHandler, context);
        }

    }


    /**
     * The server registration handler.
     */
    class ServerRegistrationRequestHandler implements ManagementRequestHandler<Void, Void> {

        @Override
        public void handleRequest(final DataInput input, final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<Void> context) throws IOException {
            final String serverName = input.readUTF();
            final int operationId = input.readInt();
            serverProcessName = serverName;
            ROOT_LOGGER.serverConnected(serverName, context.getChannel());
            // Execute the registration request
            context.executeAsync(new ManagementRequestContext.AsyncTask<Void>() {
                @Override
                public void execute(final ManagementRequestContext<Void> context) throws Exception {
                    try {
                        final OperationStepHandler stepHandler = new ServerRegistrationStepHandler(serverName, context);
                        final ModelNode result = operationExecutor.joinActiveOperation(EMPTY_OP.getOperation(), OperationMessageHandler.DISCARD, ModelController.OperationTransactionControl.COMMIT, stepHandler, operationId);
                        if(! SUCCESS.equals(result.get(OUTCOME).asString())) {
                            safeWriteResponse(context.getChannel(), context.getRequestHeader(), DomainServerProtocol.PARAM_ERROR);
                        }
                    } catch (Exception e) {
                        safeWriteResponse(context, e);
                    }
                    resultHandler.done(null);
                }
            }, registrations);
        }
    }

    /**
     * The server registration step handler. This will acquire the controller lock and hold it until the server proxy is
     * registered. Once the server proxy is registered subsequent write operations to the server will have to acquire the
     * server controller lock - which may block until the server is fully started.
     */
    class ServerRegistrationStepHandler implements OperationStepHandler {

        private String serverName;
        private String serverProcessName;
        private ManagementRequestContext<Void> comm;

        ServerRegistrationStepHandler(String serverName, ManagementRequestContext<Void> comm) {
            this.serverProcessName = serverName;
            this.serverName = serverInventory.getProcessServerName(serverProcessName);
            this.comm = comm;
        }

        @Override
        public void execute(final OperationContext context, final ModelNode operation) throws OperationFailedException {
            // Lock down the controller
            context.acquireControllerLock();
            // Read the complete domain model
            final ModelNode domainModel = Resource.Tools.readModel(context.readResourceFromRoot(PathAddress.EMPTY_ADDRESS, true));
            // Create the boot updates
            final String hostControllerName = domainController.getLocalHostInfo().getLocalHostName();
            final ModelNode hostModel = domainModel.require(HOST).require(hostControllerName);
            final ModelNode updates = ManagedServerOperationsFactory.createBootUpdates(serverName, domainModel, hostModel, domainController, expressionResolver);
            // Register the remote communication
            final ProxyController controller = serverInventory.serverCommunicationRegistered(serverProcessName, channelHandler);
            try {
                // Send the boot updates
                final FlushableDataOutput output = comm.writeMessage(ManagementResponseHeader.create(comm.getRequestHeader()));
                try {
                    output.write(DomainServerProtocol.PARAM_OK);
                    updates.writeExternal(output);
                    output.close();
                } finally {
                    StreamUtils.safeClose(output);
                }
            } catch (IOException e) {
                context.getFailureDescription().set(e.getMessage());
                return;
            }

            context.completeStep(new OperationContext.ResultHandler() {
                @Override
                public void handleResult(OperationContext.ResultAction resultAction, OperationContext context, ModelNode operation) {
                    if(resultAction == OperationContext.ResultAction.KEEP) {
                        // Register the server proxy
                        domainController.registerRunningServer(controller);
                    }
                }
            });
        }
    }

    /**
     * Handler responsible for handling server reconnnects.
     */
    class ServerReconnectRequestHandler implements ManagementRequestHandler<Void, Void> {

        @Override
        public void handleRequest(final DataInput input, final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<Void> context) throws IOException {
            expectHeader(input, DomainServerProtocol.PARAM_SERVER_NAME);
            final String serverName = input.readUTF();
            final Channel channel = context.getChannel();
            ROOT_LOGGER.serverConnected(serverName, channel);
            context.executeAsync(new ManagementRequestContext.AsyncTask<Void>() {
                @Override
                public void execute(final ManagementRequestContext<Void> requestContext) throws Exception {
                    final OperationStepHandler stepHandler = new OperationStepHandler() {
                        @Override
                        public void execute(final OperationContext context, final ModelNode operation) throws OperationFailedException {
                            // Acquire controller lock
                            context.acquireControllerLock();
                            // Check if the server is still in sync with the domain model
                            // TODO WFCORE-990 this should involve shipping the resource tree to the server, which then compares
                            // its local model. This would be done in the prepare phase of a transactional request.
                            // The ResultHandler of this step would then publish the server's state, and register
                            // the server's proxy controller with DomainModelControllerService
                            final byte param;
                            if(serverInventory.serverReconnected(serverName, channelHandler)) {
                                param = DomainServerProtocol.PARAM_OK;
                            } else {
                                param = DomainServerProtocol.PARAM_RESTART_REQUIRED;
                            }
                            // Notify the server whether configuration is still in sync or it requires a reload
                            safeWriteResponse(channel, requestContext.getRequestHeader(), param);
                            context.completeStep(OperationContext.RollbackHandler.NOOP_ROLLBACK_HANDLER);
                        }
                    };
                    try {
                        final ModelNode result = operationExecutor.execute(EMPTY_OP, OperationMessageHandler.DISCARD, ModelController.OperationTransactionControl.COMMIT, stepHandler);
                        if(! SUCCESS.equals(result.get(OUTCOME).asString())) {
                            safeWriteResponse(context.getChannel(), context.getRequestHeader(), DomainServerProtocol.PARAM_ERROR);
                        }
                    } catch (Exception e) {
                        safeWriteResponse(context, e);
                    } finally {
                        resultHandler.done(null);
                    }
                }
            });
        }

    }

    /**
     * The get file request.
     */
    private class GetFileOperation implements ManagementRequestHandler<Void, Void> {

        @Override
        public void handleRequest(DataInput input, ActiveOperation.ResultHandler<Void> resultHandler, ManagementRequestContext<Void> context)
                throws IOException {
            HostControllerLogger.ROOT_LOGGER.tracef("Handling GetFileOperation with id %d", context.getOperationId());
            final RemoteFileRequestAndHandler.RootFileReader reader = new RemoteFileRequestAndHandler.RootFileReader() {
                public File readRootFile(byte rootId, String filePath) throws RequestProcessingException {
                    byte[] hash = HashUtil.hexStringToByteArray(filePath);
                    return deploymentFileRepository.getDeploymentRoot(new ContentReference(filePath, hash));
                }
            };
            ServerToHostRemoteFileRequestAndHandler.INSTANCE.handleRequest(input, reader, resultHandler, context);
        }
    }

    /**
     * Handler responsible for handling the server started notification.
     */
    private class ServerStartedHandler implements ManagementRequestHandler<Void, ServerInventory> {

        private final String serverProcessName;
        private ServerStartedHandler(String serverProcessName) {
            this.serverProcessName = serverProcessName;
        }

        @Override
        public void handleRequest(final DataInput input, final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<ServerInventory> context) throws IOException {
            final byte param = input.readByte(); // Started / Failed PARAM_OK
            final String message = input.readUTF(); // Server started/failed message
            context.executeAsync(new ManagementRequestContext.AsyncTask<ServerInventory>() {
                @Override
                public void execute(ManagementRequestContext<ServerInventory> serverInventoryManagementRequestContext) throws Exception {
                    try {
                        final ServerInventory inventory = context.getAttachment();
                        if(param == DomainServerProtocol.PARAM_OK) {
                            inventory.serverStarted(serverProcessName);
                        } else {
                            inventory.serverStartFailed(serverProcessName);
                        }
                    } finally {
                        resultHandler.done(null);
                    }
                }
            });
        }
    }

    /**
     * Handler responsible for handling a server instability notification.
     */
    private class ServerUnstableHandler implements ManagementRequestHandler<Void, ServerInventory> {

        private final String serverProcessName;
        private ServerUnstableHandler(String serverProcessName) {
            this.serverProcessName = serverProcessName;
        }

        @Override
        public void handleRequest(final DataInput input, final ActiveOperation.ResultHandler<Void> resultHandler, final ManagementRequestContext<ServerInventory> context) throws IOException {
            context.executeAsync(new ManagementRequestContext.AsyncTask<ServerInventory>() {
                @Override
                public void execute(ManagementRequestContext<ServerInventory> serverInventoryManagementRequestContext) throws Exception {
                    try {
                        serverInventory.serverUnstable(serverProcessName);
                    } finally {
                        resultHandler.done(null);
                    }
                }
            });
        }
    }

    protected static void safeWriteResponse(final ManagementRequestContext<?> context, final Exception error) {
        safeWriteResponse(context.getChannel(), context.getRequestHeader(), error);
    }

    protected static void safeWriteResponse(final Channel channel, final ManagementProtocolHeader header, final Exception error) {
        if(header.getType() == ManagementProtocol.TYPE_REQUEST) {
            try {
                writeResponse(channel, (ManagementRequestHeader) header, error);
            } catch(IOException ioe) {
                HostControllerLogger.ROOT_LOGGER.tracef(ioe, "failed to write error response for %s on channel: %s", header, channel);
            }
        }
    }

    protected static void writeResponse(final Channel channel, final ManagementRequestHeader header, final Exception error) throws IOException {
        final ManagementResponseHeader response = ManagementResponseHeader.create(header, error);
        final MessageOutputStream output = channel.writeMessage();
        try {
            writeHeader(response, output);
            output.write(ManagementProtocol.RESPONSE_END);
            output.close();
        } finally {
            StreamUtils.safeClose(output);
        }
    }

    protected static void safeWriteResponse(final Channel channel, final ManagementProtocolHeader header, byte param) {
        if(header.getType() == ManagementProtocol.TYPE_REQUEST) {
            try {
                writeResponse(channel, (ManagementRequestHeader) header, param);
            } catch(IOException ioe) {
                HostControllerLogger.ROOT_LOGGER.tracef(ioe, "failed to write error response for %s on channel: %s", header, channel);
            }
        }
    }

    protected static void writeResponse(final Channel channel, final ManagementRequestHeader header, final byte param) throws IOException {
        final ManagementResponseHeader response = ManagementResponseHeader.create(header);
        final MessageOutputStream output = channel.writeMessage();
        try {
            writeHeader(response, output);
            output.write(param);
            output.write(ManagementProtocol.RESPONSE_END);
            output.close();
        } finally {
            StreamUtils.safeClose(output);
        }
    }

    protected static void writeHeader(final ManagementProtocolHeader header, final OutputStream os) throws IOException {
        final FlushableDataOutput output = ProtocolUtils.wrapAsDataOutput(os);
        header.write(output);
    }

}
