package com.taobao.metamorphosis.gregor.master;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.easymock.IAnswer;
import org.easymock.classextension.EasyMock;
import org.easymock.classextension.IMocksControl;
import org.junit.Before;
import org.junit.Test;

import com.taobao.gecko.core.command.ResponseCommand;
import com.taobao.gecko.core.util.OpaqueGenerator;
import com.taobao.gecko.service.Connection;
import com.taobao.gecko.service.RemotingClient;
import com.taobao.gecko.service.SingleRequestCallBackListener;
import com.taobao.metamorphosis.network.BooleanCommand;
import com.taobao.metamorphosis.network.HttpStatus;
import com.taobao.metamorphosis.network.PutCommand;
import com.taobao.metamorphosis.network.SyncCommand;
import com.taobao.metamorphosis.server.BrokerZooKeeper;
import com.taobao.metamorphosis.server.assembly.ExecutorsManager;
import com.taobao.metamorphosis.server.network.PutCallback;
import com.taobao.metamorphosis.server.network.SessionContext;
import com.taobao.metamorphosis.server.network.SessionContextHolder;
import com.taobao.metamorphosis.server.network.SessionContextImpl;
import com.taobao.metamorphosis.server.stats.StatsManager;
import com.taobao.metamorphosis.server.store.Location;
import com.taobao.metamorphosis.server.store.MessageStore;
import com.taobao.metamorphosis.server.store.MessageStoreManager;
import com.taobao.metamorphosis.server.utils.MetaConfig;
import com.taobao.metamorphosis.utils.IdWorker;
import com.taobao.metamorphosis.utils.MessageFlagUtils;


public class SamsaCommandProcessorUnitTest {

    protected MessageStoreManager storeManager;
    protected MetaConfig metaConfig;
    protected Connection conn;
    protected IMocksControl mocksControl;
    protected SamsaCommandProcessor commandProcessor;
    protected StatsManager statsManager;
    protected IdWorker idWorker;
    protected BrokerZooKeeper brokerZooKeeper;
    protected ExecutorsManager executorsManager;
    protected SessionContext sessionContext;
    protected RemotingClient remotingClient;
    final String topic = "SamsaCommandProcessorUnitTest";
    private final String slaveUrl = "meta://localhost:8124";


    protected void mock() {

        this.metaConfig = new MetaConfig();
        this.mocksControl = EasyMock.createControl();
        this.storeManager = this.mocksControl.createMock(MessageStoreManager.class);
        this.conn = this.mocksControl.createMock(Connection.class);
        this.remotingClient = this.mocksControl.createMock(RemotingClient.class);
        this.sessionContext = new SessionContextImpl(null, this.conn);
        EasyMock.expect(this.conn.getAttribute(SessionContextHolder.GLOBAL_SESSION_KEY)).andReturn(this.sessionContext)
            .anyTimes();
        this.statsManager = new StatsManager(new MetaConfig(), null, null, null);
        this.idWorker = this.mocksControl.createMock(IdWorker.class);
        this.brokerZooKeeper = this.mocksControl.createMock(BrokerZooKeeper.class);
        this.executorsManager = this.mocksControl.createMock(ExecutorsManager.class);
        this.commandProcessor = new SamsaCommandProcessor();
        this.commandProcessor.setMetaConfig(this.metaConfig);
        this.commandProcessor.setStoreManager(this.storeManager);
        this.commandProcessor.setStatsManager(this.statsManager);
        this.commandProcessor.setBrokerZooKeeper(this.brokerZooKeeper);
        this.commandProcessor.setIdWorker(this.idWorker);
        this.commandProcessor.setExecutorsManager(this.executorsManager);
        this.commandProcessor.setRemotingClient(this.remotingClient);
        this.commandProcessor.setSlaveUrl(this.slaveUrl);
    }


    @Before
    public void setUp() {
        this.mock();
        OpaqueGenerator.resetOpaque();
    }


    @Test
    public void testProcessPutRequestNormal() throws Exception {
        final int partition = 1;
        final int opaque = -1;
        final long offset = 1024L;
        final byte[] data = new byte[1024];
        final int flag = MessageFlagUtils.getFlag(null);
        final PutCommand request = new PutCommand(this.topic, partition, data, null, flag, opaque);
        final long msgId = 100000L;
        EasyMock.expect(this.remotingClient.isConnected(this.slaveUrl)).andReturn(true);
        final MessageStore store = this.mocksControl.createMock(MessageStore.class);
        EasyMock.expect(this.idWorker.nextId()).andReturn(msgId);
        EasyMock.expect(this.storeManager.getOrCreateMessageStore(this.topic, partition)).andReturn(store);
        final BooleanCommand expectResp =
                new BooleanCommand(opaque, HttpStatus.Success, msgId + " " + partition + " " + offset);
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final PutCallback cb = new PutCallback() {

            @Override
            public void putComplete(final ResponseCommand resp) {
                invoked.set(true);
                if (!expectResp.equals(resp)) {
                    throw new RuntimeException();
                }
            }
        };
        final SamsaCommandProcessor.SyncAppendCallback apdcb =
                this.commandProcessor.new SyncAppendCallback(partition,
                    this.metaConfig.getBrokerId() + "-" + partition, request, msgId, cb);
        store.append(msgId, request, apdcb);
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((SamsaCommandProcessor.SyncAppendCallback) EasyMock.getCurrentArguments()[2])
                    .appendComplete(new Location(offset, 1024));
                return null;
            }

        });
        this.remotingClient.sendToGroup(this.slaveUrl, new SyncCommand(request.getTopic(), partition,
            request.getData(), msgId, request.getFlag(), OpaqueGenerator.getNextOpaque()), apdcb);
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((SingleRequestCallBackListener) EasyMock.getCurrentArguments()[2]).onResponse(new BooleanCommand(
                    OpaqueGenerator.getNextOpaque(), HttpStatus.Success, null), SamsaCommandProcessorUnitTest.this.conn);
                return null;
            }

        });
        this.brokerZooKeeper.registerTopicInZk(this.topic);
        EasyMock.expectLastCall();
        this.mocksControl.replay();
        OpaqueGenerator.resetOpaque();
        this.commandProcessor.processPutCommand(request, this.sessionContext, cb);
        this.mocksControl.verify();
        assertEquals(0, this.statsManager.getCmdPutFailed());
        // Must be invoked
        assertTrue(invoked.get());
    }


    @Test
    public void testProcessPutRequest_SlaveDisconnected() throws Exception {
        final int partition = 1;
        final int opaque = -1;
        final long offset = 1024L;
        final byte[] data = new byte[1024];
        final int flag = MessageFlagUtils.getFlag(null);
        final PutCommand request = new PutCommand(this.topic, partition, data, null, flag, opaque);
        final long msgId = 100000L;
        // Slave is disconnected
        EasyMock.expect(this.remotingClient.isConnected(this.slaveUrl)).andReturn(false);
        final BooleanCommand expectResp =
                new BooleanCommand(opaque, HttpStatus.InternalServerError, "Slave is disconnected ");
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final PutCallback cb = new PutCallback() {

            @Override
            public void putComplete(final ResponseCommand resp) {
                invoked.set(true);
                if (!expectResp.equals(resp)) {
                    throw new RuntimeException();
                }
            }
        };
        this.brokerZooKeeper.registerTopicInZk(this.topic);
        EasyMock.expectLastCall();
        this.mocksControl.replay();
        OpaqueGenerator.resetOpaque();
        this.commandProcessor.processPutCommand(request, this.sessionContext, cb);
        this.mocksControl.verify();
        assertEquals(1, this.statsManager.getCmdPutFailed());
        // Must be invoked
        assertTrue(invoked.get());
    }


    @Test
    public void testProcessPutRequestSlaveFailed() throws Exception {
        final int partition = 1;
        final int opaque = -1;
        final long offset = 1024L;
        final byte[] data = new byte[1024];
        final int flag = MessageFlagUtils.getFlag(null);
        final PutCommand request = new PutCommand(this.topic, partition, data, null, flag, opaque);
        final long msgId = 100000L;
        EasyMock.expect(this.remotingClient.isConnected(this.slaveUrl)).andReturn(true);
        final MessageStore store = this.mocksControl.createMock(MessageStore.class);
        EasyMock.expect(this.idWorker.nextId()).andReturn(msgId);
        EasyMock.expect(this.storeManager.getOrCreateMessageStore(this.topic, partition)).andReturn(store);
        final BooleanCommand expectResp =
                new BooleanCommand(opaque, HttpStatus.InternalServerError, "Put message to slave failed");
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final PutCallback cb = new PutCallback() {

            @Override
            public void putComplete(final ResponseCommand resp) {
                invoked.set(true);
                if (!expectResp.equals(resp)) {
                    throw new RuntimeException();
                }
            }
        };
        final SamsaCommandProcessor.SyncAppendCallback apdcb =
                this.commandProcessor.new SyncAppendCallback(partition,
                    this.metaConfig.getBrokerId() + "-" + partition, request, msgId, cb);
        store.append(msgId, request, apdcb);
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((SamsaCommandProcessor.SyncAppendCallback) EasyMock.getCurrentArguments()[2])
                    .appendComplete(new Location(offset, 1024));
                return null;
            }

        });
        this.remotingClient.sendToGroup(this.slaveUrl, new SyncCommand(request.getTopic(), partition,
            request.getData(), msgId, request.getFlag(), OpaqueGenerator.getNextOpaque()), apdcb);
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((SingleRequestCallBackListener) EasyMock.getCurrentArguments()[2]).onResponse(new BooleanCommand(
                    OpaqueGenerator.getNextOpaque(), HttpStatus.InternalServerError, "Put to slave failed"),
                    SamsaCommandProcessorUnitTest.this.conn);
                return null;
            }

        });
        this.brokerZooKeeper.registerTopicInZk(this.topic);
        EasyMock.expectLastCall();
        this.mocksControl.replay();
        OpaqueGenerator.resetOpaque();
        this.commandProcessor.processPutCommand(request, this.sessionContext, cb);
        this.mocksControl.verify();
        assertEquals(1, this.statsManager.getCmdPutFailed());
        // Must be invoked
        assertTrue(invoked.get());
    }


    @Test
    public void testProcessPutRequest_SlaveFailed() throws Exception {
        final int partition = 1;
        final int opaque = -1;
        final long offset = -1;
        final byte[] data = new byte[1024];
        final int flag = MessageFlagUtils.getFlag(null);
        final PutCommand request = new PutCommand(this.topic, partition, data, null, flag, opaque);
        final long msgId = 100000L;
        EasyMock.expect(this.remotingClient.isConnected(this.slaveUrl)).andReturn(true);
        final MessageStore store = this.mocksControl.createMock(MessageStore.class);
        EasyMock.expect(this.idWorker.nextId()).andReturn(msgId);
        EasyMock.expect(this.storeManager.getOrCreateMessageStore(this.topic, partition)).andReturn(store);
        final BooleanCommand expectResp =
                new BooleanCommand(opaque, HttpStatus.InternalServerError, "Put message to master failed");
        final AtomicBoolean invoked = new AtomicBoolean(false);
        final PutCallback cb = new PutCallback() {

            @Override
            public void putComplete(final ResponseCommand resp) {
                invoked.set(true);
                if (!expectResp.equals(resp)) {
                    throw new RuntimeException();
                }
            }
        };
        final SamsaCommandProcessor.SyncAppendCallback apdcb =
                this.commandProcessor.new SyncAppendCallback(partition,
                    this.metaConfig.getBrokerId() + "-" + partition, request, msgId, cb);
        store.append(msgId, request, apdcb);
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((SamsaCommandProcessor.SyncAppendCallback) EasyMock.getCurrentArguments()[2])
                    .appendComplete(new Location(offset, 1024));
                return null;
            }

        });
        this.remotingClient.sendToGroup(this.slaveUrl, new SyncCommand(request.getTopic(), partition,
            request.getData(), msgId, request.getFlag(), OpaqueGenerator.getNextOpaque()), apdcb);
        EasyMock.expectLastCall().andAnswer(new IAnswer<Object>() {

            @Override
            public Object answer() throws Throwable {
                ((SingleRequestCallBackListener) EasyMock.getCurrentArguments()[2]).onResponse(new BooleanCommand(
                    OpaqueGenerator.getNextOpaque(), HttpStatus.Success, null), SamsaCommandProcessorUnitTest.this.conn);
                return null;
            }

        });
        this.brokerZooKeeper.registerTopicInZk(this.topic);
        EasyMock.expectLastCall();
        this.mocksControl.replay();
        OpaqueGenerator.resetOpaque();
        this.commandProcessor.processPutCommand(request, this.sessionContext, cb);
        this.mocksControl.verify();
        assertEquals(1, this.statsManager.getCmdPutFailed());
        // Must be invoked
        assertTrue(invoked.get());
    }

}
