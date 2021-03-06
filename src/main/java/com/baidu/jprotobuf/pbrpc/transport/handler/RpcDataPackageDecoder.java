/*
 * Copyright 2002-2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.baidu.jprotobuf.pbrpc.transport.handler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.frame.FrameDecoder;
import org.jboss.netty.util.internal.ConcurrentHashMap;

import com.baidu.jprotobuf.pbrpc.data.ProtocolConstant;
import com.baidu.jprotobuf.pbrpc.data.RpcDataPackage;
import com.baidu.jprotobuf.pbrpc.data.RpcHeadMeta;

/**
 * Decode RpcDataPackage from received bytes
 * 
 * @author xiemalin
 * @since 1.0
 * @see RpcDataPackage
 */
public class RpcDataPackageDecoder extends FrameDecoder {

    /**
     * Default chunk package wait time out check interval
     */
    private static final int DEFAULT_CLEANUP_INTERVAL = 1000;

    private static Logger LOG = Logger.getLogger(RpcDataPackageDecoder.class.getName());
    
    private static final Map<Long, RpcDataPackage> tempTrunkPackages = new ConcurrentHashMap<Long, RpcDataPackage>();
    
    private ExecutorService es;;
    private boolean stopChunkPackageTimeoutClean = false;
    
    
    /**
     * @param chunkPackageTimeout
     */
    public RpcDataPackageDecoder(final int chunkPackageTimeout) {
        super();
        if (chunkPackageTimeout <= 0) {
            return;
        }
        
        es = Executors.newSingleThreadExecutor();
        es.execute(new Runnable() {
            
            public void run() {
                while (!stopChunkPackageTimeoutClean) {
                    
                    if (!tempTrunkPackages.isEmpty()) {
                        
                        Map<Long, RpcDataPackage> currentCheckPackage;
                        currentCheckPackage = new HashMap<Long, RpcDataPackage>(tempTrunkPackages);
                        
                        Iterator<Entry<Long, RpcDataPackage>> iter = currentCheckPackage.entrySet().iterator();
                        while (iter.hasNext()) {
                            Entry<Long, RpcDataPackage> entry = iter.next();
                            
                            if (entry.getValue().getTimeStamp() + chunkPackageTimeout > System.currentTimeMillis()) {
                                // get time out chunk package, do clean action
                                tempTrunkPackages.remove(entry.getValue());
                                LOG.log(Level.SEVERE, "Found chunk package time out long than " + chunkPackageTimeout
                                        + "(ms) will clean up correlationId:"
                                        + entry.getValue().getRpcMeta().getCorrelationId());
                            }
                        }
                    }
                    
                    try {
                        Thread.sleep(DEFAULT_CLEANUP_INTERVAL);
                    } catch (Exception e) {
                        LOG.log(Level.SEVERE, e.getMessage(), e);
                    }
                    
                }
                
            }
        });
    }

    /*
     * (non-Javadoc)
     * 
     * @see
     * org.jboss.netty.handler.codec.frame.FrameDecoder#decode(org.jboss.netty
     * .channel.ChannelHandlerContext, org.jboss.netty.channel.Channel,
     * org.jboss.netty.buffer.ChannelBuffer)
     */
    @Override
    protected Object decode(ChannelHandlerContext ctx, Channel channel, ChannelBuffer buf) throws Exception {

        // Make sure if the length field was received.
        if (buf.readableBytes() < RpcHeadMeta.SIZE) {
            // The length field was not received yet - return null.
            // This method will be invoked again when more packets are
            // received and appended to the buffer.
            return null;
        }

        // The length field is in the buffer.

        // Mark the current buffer position before reading the length field
        // because the whole frame might not be in the buffer yet.
        // We will reset the buffer position to the marked position if
        // there's not enough bytes in the buffer.
        buf.markReaderIndex();

        // Read the RPC head
        long rpcMessageDecoderStart = System.nanoTime();
        ByteBuffer buffer = buf.toByteBuffer(buf.readerIndex(), RpcHeadMeta.SIZE);

        buffer.order(ByteOrder.LITTLE_ENDIAN);
        byte[] bytes = new byte[RpcHeadMeta.SIZE];
        buffer.get(bytes);

        RpcHeadMeta headMeta = new RpcHeadMeta();
        headMeta.read(bytes);

        // get total message size
        int messageSize = headMeta.getMessageSize() + RpcHeadMeta.SIZE;

        // Make sure if there's enough bytes in the buffer.
        if (buf.readableBytes() < messageSize) {
            // The whole bytes were not received yet - return null.
            // This method will be invoked again when more packets are
            // received and appended to the buffer.

            // Reset to the marked position to read the length field again
            // next time.
            buf.resetReaderIndex();

            return null;
        }

        // check magic code
        String magicCode = headMeta.getMagicCodeAsString();
        if (!ProtocolConstant.MAGIC_CODE.equals(magicCode)) {
            throw new Exception("Error magic code:" + magicCode);
        }
        // There's enough bytes in the buffer. Read it.
        byte[] totalBytes = new byte[messageSize];
        buf.readBytes(totalBytes, 0, messageSize);

        RpcDataPackage rpcDataPackage = new RpcDataPackage();
        rpcDataPackage.setTimeStamp(System.currentTimeMillis());
        rpcDataPackage.read(totalBytes);
        
        // check if a chunk package
        if (rpcDataPackage.isChunkPackage()) {
            
            Long chunkStreamId = rpcDataPackage.getChunkStreamId();
            
            RpcDataPackage chunkDataPackage = tempTrunkPackages.get(chunkStreamId);
            if (chunkDataPackage == null) {
                chunkDataPackage = rpcDataPackage;
                tempTrunkPackages.put(chunkStreamId, rpcDataPackage);
            } else {
                chunkDataPackage.mergeData(rpcDataPackage.getData());
            }
            
            if (rpcDataPackage.isFinalPackage()) {
                chunkDataPackage.chunkInfo(chunkStreamId, -1);
                tempTrunkPackages.remove(chunkStreamId);
                
                return chunkDataPackage;
            }
            
            return null;
        }

        long rpcMessageDecoderEnd = System.nanoTime();
        LOG.log(Level.FINE, "[profiling] nshead decode cost : " + (rpcMessageDecoderEnd - rpcMessageDecoderStart)
                / 1000);

        return rpcDataPackage;
    }

    /**
     * 
     */
    public void close() {
        stopChunkPackageTimeoutClean = true;
        if (es != null) {
            es.shutdown();
        }
        
    }

}
