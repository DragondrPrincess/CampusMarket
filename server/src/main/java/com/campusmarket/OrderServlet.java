package com.campusmarket;

import com.campusmarket.auth.ApiException;
import com.campusmarket.dao.OrderDao;
import com.campusmarket.service.AuthService;
import com.google.gson.*;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.sql.SQLException;
import java.util.*;

@WebServlet(urlPatterns={"/api/v1/orders","/api/v1/orders/*"})
public final class OrderServlet extends HttpServlet {
    @Override protected void service(HttpServletRequest r,HttpServletResponse s)throws IOException {
        r.setCharacterEncoding("UTF-8");
        try{
            long user=new AuthService().requireUser(r.getHeader("Authorization")).id();
            OrderDao dao=new OrderDao();Object data;int status=200;String path=r.getPathInfo(),method=r.getMethod();
            if(path==null&&method.equals("POST")){
                JsonObject body=UserServlet.body(r);long product=positive(body,"productId");
                JsonElement key=body.get("requestId");if(key==null||!key.isJsonPrimitive()||!key.getAsJsonPrimitive().isString()||!key.getAsString().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw bad("下单请求编号应为UUID");
                Map<String,Object> order=dao.create(user,product,key.getAsString().toLowerCase(Locale.ROOT));data=order;status=Boolean.TRUE.equals(order.get("replayed"))?200:201;
            }else if(path==null&&method.equals("GET")){
                String role=r.getParameter("role");data=dao.list(user,role==null?"buyer":role,page(r,"page",1,10000),page(r,"pageSize",20,50));
            }else if(path!=null&&method.equals("GET"))data=dao.detail(user,PublishingServlet.id(path));
            else if(path!=null&&method.equals("POST")&&path.matches("/[1-9][0-9]{0,17}/[a-z-]+")){
                int slash=path.lastIndexOf('/');long id=PublishingServlet.id(path.substring(0,slash));String action=switch(path.substring(slash+1)){
                    case "confirm"->"CONFIRM";case "simulate-pay"->"PAY";case "deliver"->"DELIVER";case "receive"->"RECEIVE";case "cancel"->"CANCEL";default->throw bad("不支持的订单操作");};
                data=dao.act(user,id,action);
            }else throw new ApiException(405,40501,"不支持的订单请求方法或路径");
            PublishingServlet.write(s,status,0,"ok",data);
        }catch(ApiException e){PublishingServlet.write(s,e.status,e.code,e.getMessage(),null);}
        catch(JsonParseException|IllegalStateException|UnsupportedOperationException e){PublishingServlet.write(s,400,40001,"订单参数格式不正确",null);}
        catch(SQLException e){getServletContext().log("Order SQL state="+e.getSQLState()+"; code="+e.getErrorCode());PublishingServlet.write(s,503,50301,"订单服务暂不可用，请使用原请求重试",null);}
    }
    private static long positive(JsonObject body,String key){JsonElement value=body.get(key);if(value==null||!value.isJsonPrimitive()||!value.getAsString().matches("[1-9][0-9]{0,17}"))throw bad("商品编号不正确");return Long.parseLong(value.getAsString());}
    private static int page(HttpServletRequest r,String key,int fallback,int max){String v=r.getParameter(key);if(v==null)return fallback;try{int n=Integer.parseInt(v);if(n>=1&&n<=max)return n;}catch(NumberFormatException ignored){}throw bad("分页参数不正确");}
    private static ApiException bad(String message){return new ApiException(400,40001,message);}
}
