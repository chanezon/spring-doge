package demo;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.Mongo;
import com.mongodb.gridfs.GridFSDBFile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.MongoDbFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoDbFactory;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsCriteria;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.FileCopyUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.servlet.MultipartConfigElement;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * @author Josh Long (josh@joshlong.com)
 */
@Configuration
@ComponentScan
@EnableAutoConfiguration
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    // NB: this should be taken care of for u in a imminent Spring Boot release.
    @Bean
    MongoDbFactory mongoDbFactory(Mongo mongo) throws Exception {
        return new SimpleMongoDbFactory(mongo, "fs");
    }

    @Bean
    GridFsTemplate gridFsTemplate(MongoDbFactory mongoDbFactory,
                                  MongoTemplate mongoTemplate) {
        return new GridFsTemplate(mongoDbFactory, mongoTemplate.getConverter());
    }
    //

    @Bean
    MultipartConfigElement multipartConfigElement() { // needed for file uploads
        return new MultipartConfigElement("");
    }
}


@Controller
class PhotoUploadMvcController {

    private final PhotoService photoService;

    @Autowired
    public PhotoUploadMvcController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @RequestMapping ("client")
    String client(){
        return "client" ;
    }


}


@RestController
@RequestMapping(value = PhotoUploadRestController.PHOTO_URI)
class PhotoUploadRestController {

    public static final String PHOTO_URI = "/users/{user}/photo";
    private final PhotoService photoService;

    @Autowired
    public PhotoUploadRestController(PhotoService photoService) {
        this.photoService = photoService;
    }

    @RequestMapping(method = RequestMethod.GET)
    ResponseEntity<byte[]> read(@PathVariable long user) throws IOException {
        Photo photo = photoService.readPhoto(user);
        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.setContentType(MediaType.parseMediaType(photo.getContentType()));
        return new ResponseEntity<>(photo.getPhoto(), httpHeaders, HttpStatus.OK);
    }

    @RequestMapping(method = RequestMethod.POST)
    HttpEntity<Void> write(@PathVariable long user, @RequestParam MultipartFile file) throws Throwable {
        byte bytesForProfilePhoto[] = FileCopyUtils.copyToByteArray(file.getInputStream());

        photoService.writePhoto(user, MediaType.parseMediaType(file.getContentType()), bytesForProfilePhoto);

        HttpHeaders httpHeaders = new HttpHeaders();
        URI uriOfPhoto = ServletUriComponentsBuilder.fromCurrentContextPath()
                .pathSegment(PhotoUploadRestController.PHOTO_URI.substring(1))
                .buildAndExpand(Collections.singletonMap("user", user))
                .toUri();
        httpHeaders.setLocation(uriOfPhoto);

        return new ResponseEntity<Void>(httpHeaders, HttpStatus.CREATED);
    }

}

interface PhotoRepository extends MongoRepository<Photo, Long> {
    Photo findByUserId(long userId);
}

@Service
class PhotoService {

    private final GridFsTemplate fileSytem;
    private final PhotoRepository photoRepository;

    @Autowired
    public PhotoService(GridFsTemplate fileSytem, PhotoRepository photoRepository) {
        this.photoRepository = photoRepository;
        this.fileSytem = fileSytem;
    }

    public void writePhoto(long userId, MediaType mediaType, byte[] photo)
            throws IOException {

        List<GridFSDBFile> gridFSDBFiles = gridFSDBFiles(userId);
        for (GridFSDBFile gridFSDBFile : gridFSDBFiles) {
            fileSytem.delete(
                    new Query().addCriteria(GridFsCriteria.whereMetaData().is(gridFSDBFile.getMetaData())));
        }
        DBObject dbObject = new BasicDBObject();
        dbObject.put("userId", userId);
        dbObject.put("when", new Date().getTime());
        dbObject.put("contentType", mediaType.toString());
        try (ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(photo)) {
            fileSytem.store(byteArrayInputStream, Long.toString(userId), dbObject);
            photoRepository.save(new Photo(userId, mediaType.toString()));
        }
    }

    public Photo readPhoto(long userId) throws IOException {
        List<GridFSDBFile> gridFSDBFileList = gridFSDBFiles(userId);
        Assert.isTrue(gridFSDBFileList.size() <= 1, "there should be 0-1 records returned");
        GridFSDBFile gridFSDBFile = gridFSDBFileList.iterator().next();

        try (InputStream inputStream = gridFSDBFile.getInputStream()) {
            DBObject metaData = gridFSDBFile.getMetaData();
            String mediaType = (String) metaData.get("contentType");
            byte[] bytes = FileCopyUtils.copyToByteArray(inputStream);
            return new Photo(userId, mediaType, bytes);
        }
    }

    private List<GridFSDBFile> gridFSDBFiles(long userId) {
        Criteria query = GridFsCriteria.whereFilename().is(Long.toString(userId));
        return fileSytem.find(new Query().addCriteria(query));
    }
}


class Photo {

    @Id
    private BigInteger id;

    //    @Transient
    private byte[] photo;

    private long userId;
    private String contentType;

    public Photo(long userId, String contentType) {
        this.contentType = contentType;
        this.userId = userId;
    }

    public Photo(long userId, String contentType, byte[] photo) {
        this.userId = userId;
        this.contentType = contentType;
        this.photo = photo;
    }

    Photo() {
    }

    @Override
    public String toString() {
        return "Photo{" +
                "userId=" + userId +
                ", photo=" + Arrays.toString(photo) +
                '}';
    }

    public BigInteger getId() {
        return id;
    }

    public long getUserId() {
        return userId;
    }

    public byte[] getPhoto() {
        return photo;
    }

    public String getContentType() {
        return contentType;
    }
}