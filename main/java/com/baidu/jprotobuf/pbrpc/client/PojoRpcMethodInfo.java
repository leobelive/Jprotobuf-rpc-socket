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

package com.baidu.jprotobuf.pbrpc.client;

import java.io.IOException;
import java.lang.reflect.Method;

import com.baidu.bjf.remoting.protobuf.Codec;
import com.baidu.bjf.remoting.protobuf.ProtobufProxy;
import com.baidu.jprotobuf.pbrpc.ProtobufRPC;

/**
 * RPC method description info for JProtobuf annotation.
 *
 * @author xiemalin
 * @since 1.0
 */
public class PojoRpcMethodInfo extends RpcMethodInfo {
    
    private Codec inputCodec;
    
    private Codec outputCodec;

    /**
     * @param method
     * @param protobufPRC
     */
    public PojoRpcMethodInfo(Method method, ProtobufRPC protobufPRC) {
        super(method, protobufPRC);
        
        Class<? extends Object> inputClass = getInputClass();
        if (inputClass != null) {
            inputCodec = ProtobufProxy.create(inputClass);
        }
        Class<? extends Object> outputClass = getOutputClass();
        if (outputClass != null) {
            outputCodec = ProtobufProxy.create(outputClass);
        }
    }

    /* (non-Javadoc)
     * @see com.baidu.jprotobuf.pbrpc.client.RpcMethodInfo#inputDecode(java.lang.Object)
     */
    @Override
    public byte[] inputEncode(Object input) throws IOException {
        if (inputCodec != null) {
            return inputCodec.encode(input);
        }
        return null;
    }

    /* (non-Javadoc)
     * @see com.baidu.jprotobuf.pbrpc.client.RpcMethodInfo#outputEncode(byte[])
     */
    @Override
    public Object outputDecode(byte[] output) throws IOException {
        if (outputCodec != null) {
            return outputCodec.decode(output);
        }
        return null;
    }
    
}
