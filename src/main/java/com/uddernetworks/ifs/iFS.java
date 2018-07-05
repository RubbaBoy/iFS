package com.uddernetworks.ifs;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.net.ConnectException;
import java.net.URISyntaxException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class iFS {

    private static final boolean SAVE_TO_DATABASE = true;

    private int threads = 20; // 25 and 10 are good for only images and downloading, 20 and 0 are for database
    private int betweenDelay = 0;

    private List<MemeType> blockedTypes = Arrays.asList();
    private long start = 0;
    private final AtomicInteger limit = new AtomicInteger(1000);
    private AtomicInteger memes = new AtomicInteger(0);
    private AtomicInteger duplicates = new AtomicInteger(0);
    private File saveDirectory;

    private boolean blockVideo;
    private boolean blockGif;
    private boolean blockImage;

    public static void main(String[] args) throws URISyntaxException {
        new iFS().start();
    }

    public void start() throws URISyntaxException {
        saveDirectory = new File(iFS.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        startTimeThread();

        blockVideo = blockedTypes.contains(MemeType.VIDEO);
        blockGif = blockedTypes.contains(MemeType.GIF);
        blockImage = blockedTypes.contains(MemeType.IMAGE);

        start = System.currentTimeMillis();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    downloadSet("/feeds/shuffle");
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();
        }
    }

    private void startTimeThread() {
        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                long timePassed = (System.currentTimeMillis() - start) / 1000;

                System.out.println("Collected " + memes.get() + " @" + (memes.get() / timePassed) + "mps \tover " + timePassed + " seconds \tDuplicate percentage: " + (Math.round(((double) duplicates.get() / (double) memes.get() * 100D) * 100D) / 100D) + "%");
            }
        }).start();
    }

    private void downloadSet(String set) throws IOException {
        try {
            Document document = Jsoup.connect("https://ifunny.co" + set).get();
            document.getElementsByClass("stream__item js-playlist-item").parallelStream().forEach(element -> {
                MemeType type;
                String src;
                String url;
                List<String> tags;
                String likes;

                Element media = element.getElementsByClass("media").get(0);

                switch (media.attr("data-type")) {
                    case "video":
                        if (blockVideo) return;
//                    type = "mp4";
                        type = MemeType.VIDEO;
                        src = media.attr("data-source");
                        url = "https://ifunny.co" + media.getElementsByTag("a").get(0).attr("href");
                        tags = element.getElementsByClass("tag__name")
                                .stream()
                                .map(span -> span.text().substring(1))
                                .collect(Collectors.toList());
                        likes = element.getElementsByClass("actioncounter__item").get(0).getElementsByClass("actionlink__text").get(0).text();
                        break;
                    case "image":
                        if (blockGif) return;
//                    type = "gif";
                        type = MemeType.GIF;
                        src = media.attr("data-source");
                        url = "https://ifunny.co" + media.getElementsByTag("a").get(0).attr("href");
                        tags = element.getElementsByClass("tag__name")
                                .stream()
                                .map(span -> span.text().substring(1))
                                .collect(Collectors.toList());
                        likes = element.getElementsByClass("actioncounter__item").get(0).getElementsByClass("actionlink__text").get(0).text();
                        break;
                    default:
                        if (blockImage) return;
//                    type = "jpg";
                        type = MemeType.IMAGE;
                        src = media.getElementsByTag("img").get(0).attr("src");
                        url = "https://ifunny.co" + media.getElementsByTag("a").get(0).attr("href");
                        tags = element.getElementsByClass("tag__name")
                                .stream()
                                .map(span -> span.text().substring(1))
                                .collect(Collectors.toList());
                        likes = element.getElementsByClass("actioncounter__item").get(0).getElementsByClass("actionlink__text").get(0).text();
                        break;
                }

                if (SAVE_TO_DATABASE) {
//                System.out.println("likes = " + likes);
                    try (Connection connection = DataSource.getConnection();
                         PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO `memes` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")) {
                        preparedStatement.setString(1, type.name());
                        preparedStatement.setString(2, src);
                        preparedStatement.setString(3, url);
                        preparedStatement.setString(4, likes);
                        preparedStatement.setString(5, tags.size() > 0 ? tags.get(0) : null);
                        preparedStatement.setString(6, tags.size() > 1 ? tags.get(1) : null);
                        preparedStatement.setString(7, tags.size() > 2 ? tags.get(2) : null);
                        preparedStatement.setString(8, tags.size() > 3 ? tags.get(3) : null);
                        preparedStatement.setString(9, tags.size() > 4 ? tags.get(4) : null);

                        preparedStatement.executeUpdate();
                    } catch (SQLException e) {
//                    e.printStackTrace();
//                    System.out.println(e.getLocalizedMessage());
                        duplicates.incrementAndGet();
                    }
                } else {
                    try {
                        URL website = new URL(src);
                        File destinationFile = new File(saveDirectory.getAbsolutePath() + File.separator + src.split("/")[4]);
                        try (InputStream is = website.openStream();
                             OutputStream os = new FileOutputStream(destinationFile)) {

                            byte[] b = new byte[2048 * 2];
                            int length;

                            while ((length = is.read(b)) != -1) {
                                os.write(b, 0, length);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                memes.incrementAndGet();
            });

            try {
                Thread.sleep(betweenDelay);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            downloadSet(document.getElementsByClass("feed__control").get(0).getElementsByTag("a").get(0).attr("href"));
        } catch (ConnectException ignored) {
            System.err.println("Timed out");
        }
    }

}
