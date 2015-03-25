package org.cbir.retrieval.service;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import retrieval.client.RetrievalClient;
import retrieval.config.ConfigClient;
import retrieval.config.ConfigServer;
import retrieval.server.RetrievalServer;
import retrieval.storage.Storage;
import retrieval.storage.exception.AlreadyIndexedException;
import retrieval.storage.exception.NoValidPictureException;
import retrieval.storage.exception.PictureTooHomogeneous;
import retrieval.storage.exception.TooMuchIndexRequestException;

import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Service class for managing retrieval server.
 */
@Service
@Transactional
public class RetrievalService {

    public static String DEFAULT_TEST_STORAGE = "default";
    public static String DEFAULT_STORAGE = "dev";
    public static String OTHER_STORAGE = "abc";

    private final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    @Inject
    private Environment env;

    @Inject
    private StoreImageService storeImageService;

    @Autowired
    ServletContext servletContext;

    public RetrievalServer initRetrievalServer() throws Exception{
        log.info("Init retrieval server");
        RetrievalServer server = null;
        String envir = "";
        if(env.getActiveProfiles().length>0) {
            envir = env.getActiveProfiles()[0];
            log.info("profile is "+ Arrays.toString(env.getActiveProfiles()) +" => " +envir);
            if (envir.equals("dev")) {
                server = buildRetrievalServerForDev();
            } else if (envir.equals("prod")) {
                server = buildRetrievalServerForProd();
            } else {
                server = buildRetrievalServerForTest();
            }
        } else server = buildRetrievalServerForTest();
        log.info("server "+ server);
        log.info("storages "+ server.getStorageList());

        if(server.getStorageList()==null || server.getStorageList().isEmpty()) {
            server.createStorage(DEFAULT_TEST_STORAGE);
        }

        servletContext.setAttribute("server",server);
        servletContext.setAttribute("client",buildRetrievalClient(server));
        return server;
    }

    public RetrievalServer getRetrievalServer() {
        return (RetrievalServer)servletContext.getAttribute("server");
    }

    public RetrievalClient getRetrievalClient() {
        return (RetrievalClient)servletContext.getAttribute("client");
    }


    public RetrievalServer buildRetrievalServerForTest() throws Exception {
        ConfigServer configServer = new ConfigServer(env.getProperty("retrieval.config.server"));
        configServer.setStoreName(env.getProperty("retrieval.store.name"));
        RetrievalServer server = new RetrievalServer(configServer,"cbir",false);
        server.createStorage(DEFAULT_TEST_STORAGE);
        return server;
    }

    public RetrievalServer buildRetrievalServerForDev() throws Exception {
        ConfigServer configServer = new ConfigServer(env.getProperty("retrieval.config.server"));
        configServer.setStoreName(env.getProperty("retrieval.store.name"));
        RetrievalServer server = new RetrievalServer(configServer,"cbir",false);

        if(configServer.getStoreName().equals("MEMORY")) {
            server.createStorage(DEFAULT_TEST_STORAGE);
            server.createStorage(OTHER_STORAGE);

            Map<String, String> properties = new TreeMap<>();
            properties.put("date", new Date().toString());

            indexPicture(server.getStorage(DEFAULT_TEST_STORAGE), ImageIO.read(new File("testdata/images/crop1.jpg")), 1l, new HashMap<>(properties));
            indexPicture(server.getStorage(DEFAULT_TEST_STORAGE), ImageIO.read(new File("testdata/images/crop2.jpg")), 2l, new HashMap<>(properties));
            indexPicture(server.getStorage(DEFAULT_TEST_STORAGE), ImageIO.read(new File("testdata/images/crop3.jpg")), 3l, new HashMap<>(properties));
            indexPicture(server.getStorage(OTHER_STORAGE), ImageIO.read(new File("testdata/images/crop4.jpg")), 4l, new HashMap<>(properties));
        }
        return server;
    }

    public RetrievalServer buildRetrievalServerForProd() throws Exception {
        ConfigServer configServer = new ConfigServer(env.getProperty("retrieval.config.server"));
        configServer.setStoreName(env.getProperty("retrieval.store.name"));
        RetrievalServer server = new RetrievalServer(configServer,"cbir",false);

        return server;
    }

    private void indexPicture(Storage storage,BufferedImage image,Long id, Map<String,String> properties) throws NoValidPictureException, AlreadyIndexedException, PictureTooHomogeneous, IOException {
        Long realId = storage.indexPicture(image,id,properties);
        storeImageService.saveIndexImage(realId,image);
    }

    private void indexPictureAsync(Storage storage,BufferedImage image,Long id, Map<String,String> properties) throws NoValidPictureException, AlreadyIndexedException, PictureTooHomogeneous, IOException, TooMuchIndexRequestException {
        Long realId = storage.addToIndexQueue(image, id, properties);
        storeImageService.saveIndexImage(realId,image);
    }


    public RetrievalClient buildRetrievalClient(RetrievalServer server) throws Exception {
        return new RetrievalClient(new ConfigClient(env.getProperty("retrieval.config.client")),server);
    }

    public void reset() throws Exception {
        initRetrievalServer();
    }

    public void indexDataset(RetrievalServer server,Path dataset) throws Exception {
        if(Files.exists(dataset)) {
            int nbstorage = 4;
            for(int i=1;i<=nbstorage;i++) {
                server.createStorage(i+"");
            }
            long id = 0;
            Files.walk(dataset).forEach(filePath -> {
                try {
                    if (Files.isRegularFile(filePath) && !Files.isHidden(filePath)) {
                        log.info("Process file: "+filePath);
                        String filename = filePath.getFileName().toString().split("\\.")[0];
                        try {
                            indexPictureAsync(
                                server.getStorage(String.valueOf(new Random().nextInt(nbstorage) + 1)),
                                ImageIO.read(filePath.toFile()), Long.parseLong(filename), new HashMap<>());
                        } catch (NoValidPictureException e) {
                            e.printStackTrace();
                        } catch (AlreadyIndexedException e) {
                            e.printStackTrace();
                        } catch (PictureTooHomogeneous pictureTooHomogeneous) {
                            pictureTooHomogeneous.printStackTrace();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (TooMuchIndexRequestException e) {
                            e.printStackTrace();
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            });
        } else throw new IOException("Path "+dataset.toAbsolutePath() +" does not exists");
    }

}
