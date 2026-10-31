package com.campusmarket;

import com.campusmarket.auth.ApiException;
import com.campusmarket.service.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.*;
import jakarta.servlet.http.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

@WebServlet(urlPatterns={"/api/v1/images","/api/v1/images/*"})
@MultipartConfig(maxFileSize=5242880,maxRequestSize=6291456,fileSizeThreshold=5242880)
public final class ImageServlet extends HttpServlet {
    private static Path directory()throws IOException {
        String configured=System.getenv("CAMPUS_UPLOAD_DIR");if(configured==null)throw new IOException("Upload directory not configured");
        Path directory=Path.of(configured).toAbsolutePath().normalize();Files.createDirectories(directory);return directory;
    }
    @Override protected void service(HttpServletRequest request,HttpServletResponse response)throws IOException {
        try{
            if(request.getMethod().equals("POST")&&request.getPathInfo()==null)upload(request,response);
            else if(request.getMethod().equals("GET")&&request.getPathInfo()!=null)download(request,response);
            else throw new ApiException(405,40501,"不支持的请求方法");
        }catch(ApiException e){PublishingServlet.write(response,e.status,e.code,e.getMessage(),null);}
        catch(IllegalStateException e){PublishingServlet.write(response,413,41301,"图片不得超过5MB",null);}
        catch(ServletException e){PublishingServlet.write(response,400,40001,"请使用有效的图片上传请求",null);}
        catch(SQLException e){getServletContext().log("Image SQL state="+e.getSQLState()+"; code="+e.getErrorCode());PublishingServlet.write(response,503,50301,"图片服务暂不可用",null);}
        catch(IOException e){if(response.isCommitted())throw e;PublishingServlet.write(response,503,50301,"图片读写失败，请重试",null);}
    }
    private void upload(HttpServletRequest request,HttpServletResponse response)throws IOException,SQLException,ServletException {
        long user=new AuthService().requireUser(request.getHeader("Authorization")).id();
        if(request.getContentType()==null||!request.getContentType().toLowerCase(Locale.ROOT).startsWith("multipart/form-data"))throw new ApiException(415,41501,"请上传JPEG或PNG图片");
        Collection<Part> parts=request.getParts();
        try{
            if(parts.size()!=1)throw new ApiException(400,40001,"每次只能上传一张图片");
            Part part=parts.iterator().next();
            if(!part.getName().equals("file")||part.getSize()==0||part.getContentType()==null||!Set.of("image/jpeg","image/png").contains(part.getContentType()))throw new ApiException(415,41501,"只支持JPEG或PNG图片");
            BufferedImage image;String format;
            // Inspect dimensions before decoding to bound memory, then re-encode without client metadata.
            try(InputStream input=part.getInputStream();ImageInputStream stream=ImageIO.createImageInputStream(input)){
                Iterator<ImageReader> readers=ImageIO.getImageReaders(stream);if(!readers.hasNext())throw new ApiException(415,41501,"图片内容无效");
                ImageReader reader=readers.next();try{reader.setInput(stream);format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if(!Set.of("jpeg","png").contains(format))throw new ApiException(415,41501,"图片内容格式不支持");
                    int w=reader.getWidth(0),h=reader.getHeight(0);if(w<1||h<1||w>4096||h>4096||(long)w*h>16000000)throw new ApiException(400,40001,"图片尺寸过大，最大4096像素");
                    image=reader.read(0);
                }finally{reader.dispose();}
            }catch(javax.imageio.IIOException e){throw new ApiException(415,41501,"图片损坏或无法解码");}
            String name=UUID.randomUUID()+"."+(format.equals("jpeg")?"jpg":"png");Path path=directory().resolve(name);
            boolean saved=false;
            try{
                try(OutputStream output=Files.newOutputStream(path,StandardOpenOption.CREATE_NEW)){if(!ImageIO.write(image,format,output))throw new IOException("Encoder missing");}
                long size=Files.size(path);if(size>5242880)throw new ApiException(413,41301,"处理后图片超过5MB，请缩小图片");
                long id;try(Connection c=Database.open();PreparedStatement sql=c.prepareStatement("INSERT INTO product_images(uploader_id,storage_name,media_type,file_size,width,height) VALUES(?,?,?,?,?,?)",Statement.RETURN_GENERATED_KEYS)){
                    sql.setLong(1,user);sql.setString(2,name);sql.setString(3,"image/"+format);sql.setLong(4,size);sql.setInt(5,image.getWidth());sql.setInt(6,image.getHeight());sql.executeUpdate();try(ResultSet r=sql.getGeneratedKeys()){r.next();id=r.getLong(1);}
                }saved=true;
                PublishingServlet.write(response,201,0,"ok",Map.of("id",id,"url","/images/"+id,"width",image.getWidth(),"height",image.getHeight()));
            }finally{image.flush();if(!saved)Files.deleteIfExists(path); /* Only this request's new, uncommitted file. */}
        }finally{for(Part part:parts)part.delete();}
    }
    private void download(HttpServletRequest request,HttpServletResponse response)throws IOException,SQLException {
        long id=PublishingServlet.id(request.getPathInfo());String name,type;long owner;boolean visible;
        try(Connection c=Database.open();PreparedStatement s=c.prepareStatement("SELECT i.storage_name,i.media_type,i.uploader_id,i.active,p.status,c.enabled,u.status FROM product_images i LEFT JOIN products p ON p.id=i.product_id LEFT JOIN categories c ON c.id=p.category_id LEFT JOIN users u ON u.id=p.seller_id WHERE i.id=?")){
            s.setLong(1,id);try(ResultSet r=s.executeQuery()){if(!r.next()||!r.getBoolean(4)||"DELETED".equals(r.getString(5)))throw new ApiException(404,40401,"图片不存在");
                name=r.getString(1);type=r.getString(2);owner=r.getLong(3);visible="ON_SALE".equals(r.getString(5))&&r.getBoolean(6)&&"ACTIVE".equals(r.getString(7));
            }
        }
        if(!visible){long user=new AuthService().requireUser(request.getHeader("Authorization")).id();if(user!=owner)throw new ApiException(403,40301,"无权查看该图片");}
        if(!name.matches("[0-9a-f-]{36}\\.(jpg|png)"))throw new IOException("Invalid stored image name");
        Path path=directory().resolve(name);if(!Files.isRegularFile(path))throw new ApiException(404,40401,"图片文件不存在");
        response.setContentType(type);response.setHeader("Cache-Control","private, no-store");response.setHeader("X-Content-Type-Options","nosniff");response.setContentLengthLong(Files.size(path));Files.copy(path,response.getOutputStream());
    }
}
