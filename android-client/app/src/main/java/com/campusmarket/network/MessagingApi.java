package com.campusmarket.network;

import java.io.IOException;
import java.util.*;

public final class MessagingApi {
    public static final class Conversation {
        public long id,productId,buyerId,sellerId,lastSeq,readSeq,unreadCount;
        public String productTitle,productStatus,lastMessage,peerNickname,viewerRole,createdAt,updatedAt;
    }
    public static final class Message {
        public long id,conversationId,seq,senderId;public String content,requestId,createdAt;public boolean mine,replayed;
    }
    public static final class Conversations {public Conversation[] items;public int page,pageSize;public long total,unreadTotal;}
    public static final class Messages {public Conversation conversation;public Message[] items;public boolean hasMoreBefore,hasMoreAfter;}
    public static final class Unread {public long unreadTotal;}
    private final UserApi transport;
    public MessagingApi(){transport=new UserApi();}
    MessagingApi(String base){transport=new UserApi(base);}
    public Conversation open(String token,long product)throws IOException{return transport.request("POST","/conversations",token,Collections.singletonMap("productId",product),Conversation.class);}
    public Conversations list(String token,int page)throws IOException {
        Conversations value=transport.request("GET","/conversations?page="+page+"&pageSize=20",token,null,Conversations.class);
        if(value.items==null||value.page!=page||value.total<0)throw new IOException("Invalid conversations");return value;
    }
    public Conversation detail(String token,long id)throws IOException{return transport.request("GET","/conversations/"+id,token,null,Conversation.class);}
    public long unread(String token)throws IOException{return transport.request("GET","/conversations/unread",token,null,Unread.class).unreadTotal;}
    public Messages messages(String token,long id,Long after,Long before)throws IOException {
        String query=after!=null?"&afterSeq="+after:before!=null?"&beforeSeq="+before:"";
        Messages value=transport.request("GET","/conversations/"+id+"/messages?limit=50"+query,token,null,Messages.class);
        if(value.items==null||value.conversation==null)throw new IOException("Invalid messages");return value;
    }
    public Message send(String token,long id,String requestId,String content)throws IOException {
        Map<String,String> body=new LinkedHashMap<>();body.put("requestId",requestId);body.put("content",content);
        return transport.request("POST","/conversations/"+id+"/messages",token,body,Message.class);
    }
    public Conversation read(String token,long id,long through)throws IOException{return transport.request("POST","/conversations/"+id+"/read",token,Collections.singletonMap("throughSeq",through),Conversation.class);}
}
