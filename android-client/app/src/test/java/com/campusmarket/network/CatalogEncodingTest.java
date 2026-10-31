package com.campusmarket.network;

import com.google.gson.Gson;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Isolated HTTP fixture using Java sockets; never connects to the real database. */
public final class CatalogEncodingTest {
    @Test public void chineseEnglishAndReservedCharactersRoundTrip() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))){
            server.setSoTimeout(5000);
            CatalogApi api=new CatalogApi("http://127.0.0.1:"+server.getLocalPort());
            for(String word:new String[]{"高等数学","English","校园 USB","C++ & 100%","羽毛球🏸",""}){
                Future<String> received=worker.submit(()->{
                    try(Socket socket=server.accept()){
                        socket.setSoTimeout(5000);
                        BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.US_ASCII));
                        String request=reader.readLine(),line;
                        while((line=reader.readLine())!=null&&!line.isEmpty()) { /* Consume HTTP headers. */ }
                        byte[] bytes=new Gson().toJson(Map.of("code",0,"data",Map.of("page",1,"total",1,"items",new Object[]{Map.of("id",1,"title",word)}))).getBytes(StandardCharsets.UTF_8);
                        OutputStream out=socket.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                        out.write(bytes);out.flush();return request;
                    }
                });
                CatalogApi.Page result=api.list(1,0,word,"latest");
                assertEquals(word,result.items[0].title);
                assertEquals("GET /products?page=1&pageSize=6&sort=latest&keyword="+URLEncoder.encode(word,"UTF-8")+" HTTP/1.1",received.get(5,TimeUnit.SECONDS));
            }
        } finally {worker.shutdownNow();}
    }
}
