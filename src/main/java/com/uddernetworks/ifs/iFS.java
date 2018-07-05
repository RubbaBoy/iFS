package com.uddernetworks.ifs;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class iFS {

    private int threads = 25; // 25 and 10 are good for only images
    private int betweenDelay = 10;

    private List<MemeType> blockedTypes = Arrays.asList();
    private long start = 0;
    private final AtomicInteger limit = new AtomicInteger(1000);
    private AtomicInteger memes = new AtomicInteger(0);
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

                System.out.println("Collected " + memes.get() + " @" + (memes.get() / timePassed) + "mps\tover " + timePassed + " seconds");
            }
        }).start();
    }

    private void downloadSet(String set) throws IOException {
//        if (limit.get() <= 0) return;
        Document document = Jsoup.connect("https://ifunny.co" + set).get();
        document.getElementsByClass("stream__item js-playlist-item").parallelStream().forEach(element -> {
//            if (limit.getAndDecrement() <= 0) return;
            String type;
            String src;
            String url;
            List<String> tags;

            Element media = element.getElementsByClass("media").get(0);

            switch (media.attr("data-type")) {
                case "video":
                    if (blockVideo) return;
                    type = "mp4";
                    src = media.attr("data-source");
                    url = "https://ifunny.co" + media.getElementsByTag("a").get(0).attr("href");
                    tags = element.getElementsByClass("tag__name")
                            .stream()
                            .map(span -> span.text().substring(1))
                            .collect(Collectors.toList());
                    break;
                case "image":
                    if (blockGif) return;
                    type = "gif";
                    src = media.attr("data-source");
                    url = "https://ifunny.co" + media.getElementsByTag("a").get(0).attr("href");
                    tags = element.getElementsByClass("tag__name")
                            .stream()
                            .map(span -> span.text().substring(1))
                            .collect(Collectors.toList());
                    break;
                default:
                    if (blockImage) return;
                    type = "jpg";
                    src = media.getElementsByTag("img").get(0).attr("src");
                    url = "https://ifunny.co" + media.getElementsByTag("a").get(0).attr("href");
                    tags = element.getElementsByClass("tag__name")
                            .stream()
                            .map(span -> span.text().substring(1))
                            .collect(Collectors.toList());
                    break;
            }

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

            memes.incrementAndGet();
        });

        try {
            Thread.sleep(betweenDelay);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        downloadSet(document.getElementsByClass("feed__control").get(0).getElementsByTag("a").get(0).attr("href"));
    }

}
