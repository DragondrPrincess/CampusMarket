package com.campusmarket;

import com.campusmarket.auth.ApiException;
import com.campusmarket.dao.MessagingDao;
import com.campusmarket.service.AuthService;
import com.google.gson.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

@WebServlet(urlPatterns={"/api/v1/conversations","/api/v1/conversations/*"})
public final class MessagingServlet extends HttpServlet {
    @Override protected void service(HttpServletRequest r,HttpServletResponse s)throws IOException {
        r.setCharacterEncoding("UTF-8");
        try{
            long user=new AuthService().requireUser(r.getHeader("Authorization")).id();MessagingDao dao=new MessagingDao();Object data;int status=200;
            String path=r.getPathInfo(),method=r.getMethod();
            if(path==null&&method.equals("POST"))data=dao.open(user,number(UserServlet.body(r),"productId",1));
            else if(path==null&&method.equals("GET"))data=dao.list(user,page(r,"page",1,10000),page(r,"pageSize",20,50));
            else if("/unread".equals(path)&&method.equals("GET"))data=Map.of("unreadTotal",dao.unread(user));
            else if(path!=null&&path.matches("/[1-9][0-9]{0,17}")&&method.equals("GET"))data=dao.detail(user,PublishingServlet.id(path));
            else if(path!=null&&path.matches("/[1-9][0-9]{0,17}/(messages|read)")){
                int slash=path.lastIndexOf('/');long id=PublishingServlet.id(path.substring(0,slash));String route=path.substring(slash+1);
                if(route.equals("messages")&&method.equals("GET")){
                    Long after=cursor(r,"afterSeq",0),before=cursor(r,"beforeSeq",1);if(after!=null&&before!=null)throw bad("不能同时指定前后消息位置");
                    data=dao.messages(user,id,after,before,page(r,"limit",50,100));
                }else if(route.equals("messages")&&method.equals("POST")){
                    JsonObject body=UserServlet.body(r);String content=string(body,"content").strip();
                    if(content.isBlank()||content.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c))||content.codePointCount(0,content.length())>1000||content.codePoints().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t'))throw bad("消息须为1至1000字有效文字");
                    String key=string(body,"requestId");if(!key.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw bad("发送请求编号应为UUID");
                    Map<String,Object> message=dao.send(user,id,key.toLowerCase(Locale.ROOT),content);data=message;status=Boolean.TRUE.equals(message.get("replayed"))?200:201;
                }else if(route.equals("read")&&method.equals("POST"))data=dao.read(user,id,number(UserServlet.body(r),"throughSeq",0));
                else throw method();
            }else throw method();
            PublishingServlet.write(s,status,0,"ok",data);
        }catch(ApiException e){PublishingServlet.write(s,e.status,e.code,e.getMessage(),null);}
        catch(JsonParseException|IllegalStateException|UnsupportedOperationException e){PublishingServlet.write(s,400,40001,"消息参数格式不正确",null);}
        catch(SQLException e){getServletContext().log("Messaging SQL state="+e.getSQLState()+"; code="+e.getErrorCode());PublishingServlet.write(s,503,50301,"消息服务暂不可用，请保留原消息重试",null);}
    }
    private static String string(JsonObject body,String key){JsonElement value=body.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsJsonPrimitive().isString())throw bad("消息字段类型不正确");return value.getAsString();}
    private static long number(JsonObject body,String key,int min){JsonElement v=body.get(key);if(v==null||!v.isJsonPrimitive())throw bad("编号不正确");return number(v.getAsString(),min);}
    private static long number(String v,int min){if(!v.matches("0|[1-9][0-9]{0,17}"))throw bad("编号或位置不正确");long n=Long.parseLong(v);if(n<min)throw bad("编号或位置不正确");return n;}
    private static Long cursor(HttpServletRequest r,String key,int min){String v=r.getParameter(key);return v==null?null:number(v,min);}
    private static int page(HttpServletRequest r,String key,int fallback,int max){String v=r.getParameter(key);if(v==null)return fallback;long n=number(v,1);if(n>max)throw bad("分页范围不正确");return (int)n;}
    private static ApiException bad(String message){return new ApiException(400,40001,message);}
    private static ApiException method(){return new ApiException(405,40501,"不支持的会话请求方法或路径");}
}
