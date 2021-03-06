/*
 * Copyright www.gdevelop.com.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.gdevelop.gwt.syncrpc;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.CookieStore;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.rpc.InvocationException;
import com.google.gwt.user.client.rpc.RpcRequestBuilder;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.SerializationStreamFactory;
import com.google.gwt.user.client.rpc.StatusCodeException;
import com.google.gwt.user.client.rpc.impl.RequestCallbackAdapter;
import com.google.gwt.user.server.rpc.SerializationPolicy;


/**
 * Base on {@link com.google.gwt.user.client.rpc.impl.RemoteServiceProxy}
 */
public class RemoteServiceSyncProxy implements SerializationStreamFactory{
  public static final boolean DUMP_PAYLOAD = Boolean.getBoolean("gwt.rpc.dumpPayload");
  
  public static class DummySerializationPolicy extends SerializationPolicy{
    public boolean shouldDeserializeFields(Class<?> clazz) {
      return clazz != null;
    }

    public boolean shouldSerializeFields(Class<?> clazz) {
      return clazz != null;
    }

    public void validateDeserialize(Class<?> clazz) throws SerializationException{
    }
    public void validateSerialize(Class<?> clazz) throws SerializationException{
    }
  }

  private String moduleBaseURL;
  private String remoteServiceURL;
  private String serializationPolicyName;
  private SerializationPolicy serializationPolicy;
  private CookieStore cookieStore;
  
  public RemoteServiceSyncProxy(String moduleBaseURL, 
                                String remoteServiceRelativePath, 
                                String serializationPolicyName, 
                                CookieStore cookieStore) {
    this.moduleBaseURL = moduleBaseURL;
    this.remoteServiceURL = moduleBaseURL + remoteServiceRelativePath;
    this.serializationPolicyName = serializationPolicyName;
    this.cookieStore = cookieStore;

    if (serializationPolicyName == null){
      serializationPolicy = new DummySerializationPolicy();
    }else{
      String policyFileName = SerializationPolicyLoader.getSerializationPolicyFileName(serializationPolicyName);
      InputStream is = getClass().getResourceAsStream("/" + policyFileName);
      try {
        if (is == null){
          // Try to get from cache
          String text = RpcPolicyFinder.getCachedPolicyFile(moduleBaseURL + policyFileName);
          if (text != null){
            is = new ByteArrayInputStream(text.getBytes("UTF8"));
          }
        }
        serializationPolicy = SerializationPolicyLoader.loadFromStream(is, null);
      } catch (Exception e) {
        throw new InvocationException("Error while loading serialization policy " 
                                      + serializationPolicyName, e);
      }finally{
        if (is != null){
          try {
            is.close();
          } catch (IOException e) {
            // Ignore this error
          }
        }
      }
    }
  }

  public SyncClientSerializationStreamReader createStreamReader(String encoded)
      throws SerializationException {
    SyncClientSerializationStreamReader reader = 
      new SyncClientSerializationStreamReader(serializationPolicy);
    reader.prepareToRead(encoded);
    return reader;
  }
  
  public SyncClientSerializationStreamWriter createStreamWriter() {
    SyncClientSerializationStreamWriter streamWriter = 
      new SyncClientSerializationStreamWriter(null, moduleBaseURL, 
      serializationPolicyName, serializationPolicy);
    streamWriter.prepareToWrite();
    
    return streamWriter;
  }
  
  public Object doInvoke(RequestCallbackAdapter.ResponseReader responseReader, 
      String requestData, AsyncCallback<Long> responseReadCallback) throws Throwable{
     HttpResponse response = null;
    InputStream is = null;
    int statusCode;
    
    if (DUMP_PAYLOAD){
      System.out.println("Send request to " + remoteServiceURL);
      System.out.println("Request payload: " + requestData);
    }
    
    // Send request
    try {
      
      URL url = new URL(remoteServiceURL);
      DefaultHttpClient client = new DefaultHttpClient ( );
      client.setCookieStore ( cookieStore );
      HttpPost httpPost = new HttpPost ( url.toURI ( ) );
      httpPost.setHeader(RpcRequestBuilder.STRONG_NAME_HEADER, serializationPolicyName);
      httpPost.setHeader(RpcRequestBuilder.MODULE_BASE_HEADER, moduleBaseURL);
      httpPost.setHeader("Content-Type", "text/x-gwt-rpc; charset=utf-8");
      httpPost.setEntity ( new StringEntity ( requestData ) );
      response = client.execute ( httpPost );
    } catch (IOException e) {
      throw new InvocationException("IOException while sending RPC request", e);
    }finally{
    }
    
    // Receive and process response
    try{
      statusCode = response.getStatusLine ( ).getStatusCode ( );
      HttpEntity responseEntity = response.getEntity ( );
      is = responseEntity.getContent ( );
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      byte[] buffer = new byte[1024];
      int len;
      while ((len = is.read(buffer)) > 0){
        baos.write(buffer, 0, len);
      }
      if ( responseReadCallback != null ) responseReadCallback.onSuccess ( new Long ( baos.size ( ) ) );
      String encodedResponse = baos.toString("UTF8");
      if (DUMP_PAYLOAD){
        System.out.println("Response code: " + statusCode);
        System.out.println("Response payload: " + encodedResponse);
      }
      
      // System.out.println("Response payload (len = " + encodedResponse.length() + "): " + encodedResponse);
      if (statusCode != HttpStatus.SC_OK) {
        throw new StatusCodeException(statusCode, encodedResponse);
      } else if (encodedResponse == null) {
        // This can happen if the XHR is interrupted by the server dying
        throw new InvocationException("No response payload");
      } else if (isReturnValue(encodedResponse)) {
        encodedResponse = encodedResponse.substring(4);
        return responseReader.read(createStreamReader(encodedResponse));
      } else if (isThrownException(encodedResponse)) {
        encodedResponse = encodedResponse.substring(4);
        Throwable throwable = (Throwable)createStreamReader(encodedResponse).readObject();
        throw throwable;
      }else{
        throw new InvocationException("Unknown response " + encodedResponse);
      }
    }catch(IOException e){
      throw new InvocationException("IOException while receiving RPC response", e);
    } catch (SerializationException e) {
      throw new InvocationException("Error while deserialization response", e);
    }finally{
      if (is != null){
        try{
          is.close();
        }catch(IOException ignore){}
      }
    }
  }

  static boolean isReturnValue(String encodedResponse) {
    return encodedResponse.startsWith("//OK");
  }
  static boolean isThrownException(String encodedResponse) {
    return encodedResponse.startsWith("//EX");
  }
}
