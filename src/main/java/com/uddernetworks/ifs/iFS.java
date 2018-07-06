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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class iFS {

    private static final boolean SAVE_TO_DATABASE = true;

    private int threads = 1; // 25 and 10 are good for only images and downloading, 20 and 0 are for database
    private int betweenDelay = 0;
    private boolean shuffle = false;

    private final int dontStartUntilReach = 48200;

    private AtomicReference<Set<Meme>> totalMemeCache = new AtomicReference<>(new HashSet<>());
    private AtomicReference<Set<Meme>> uniqueMemes = new AtomicReference<>(new HashSet<>());

    private List<MemeType> blockedTypes = Arrays.asList();
    private long start = 0;
    private final AtomicInteger limit = new AtomicInteger(1000);
    private AtomicInteger memes = new AtomicInteger(0);
    private AtomicInteger duplicates = new AtomicInteger(0);
    private AtomicReference<List<String>> seenSets = new AtomicReference<>(new ArrayList<>());
    private AtomicReference<Queue<String>> sets = new AtomicReference<>(new LinkedList<>());
    private File saveDirectory;
    private final AtomicReference<String> lastSet = new AtomicReference<>(shuffle ? "/feeds/shuffle" : "");

    private boolean blockVideo;
    private boolean blockGif;
    private boolean blockImage;

    private ExecutorService executorService = Executors.newFixedThreadPool(25);

    public static void main(String[] args) throws URISyntaxException, InterruptedException {
        new iFS().start();
    }

    public void start() throws URISyntaxException, InterruptedException {
        saveDirectory = new File(iFS.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParentFile();
        startTimeThread();

        blockVideo = blockedTypes.contains(MemeType.VIDEO);
        blockGif = blockedTypes.contains(MemeType.GIF);
        blockImage = blockedTypes.contains(MemeType.IMAGE);

        try (Connection connection = DataSource.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM `memes2`;")) {

            ResultSet resultSet = preparedStatement.executeQuery();

            while (resultSet.next()) {
                totalMemeCache.get().add(new Meme(resultSet.getString("src")));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }



        if (!shuffle) {
            for (int i = 0; i < 20; i++) {
                new Thread(() -> {
                    try {
                        while (true) {
                            startCollectingSets();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }).start();
            }
        }

        System.out.println("Starting in 1 second...");
        Thread.sleep(1000);

        start = System.currentTimeMillis();

        while (true) {
            try {
                Thread.sleep(10);
                String something = "/feeds/shuffle";
                if (!shuffle) {
                    Queue<String> got = sets.get();
                    if (got.size() == 0) continue;
                    try {
                        something = got.remove();
                    } catch (NoSuchElementException ignored) { // When in doubt, try/catch it out
                        continue;
                    }
                    if (something == null) continue;
                    sets.set(got);
                }

                String finalSomething = something;
                executorService.execute(() -> {
                    try {
//                        System.out.println("something = " + finalSomething);
//                        downloadSet(something);
                        downloadSet(finalSomething);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
            } catch (Exception ignored) {
                ignored.printStackTrace();
            }
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

                if (memes.get() > 0)
                    System.out.println("Collected " + memes.get() + " (" + (memes.get() - duplicates.get()) + " unique) @" + (memes.get() / timePassed) + "mps (" + ((memes.get() - duplicates.get()) / timePassed) + "umps) \tover " + timePassed + " seconds" + (!shuffle ? " \tTotal in queue: " + sets.get().size() : "") + " \tDuplicate percentage: " + (Math.round(((double) duplicates.get() / (double) memes.get() * 100D) * 100D) / 100D) + "%");
            }
        }).start();


        ExecutorService queryPool = Executors.newFixedThreadPool(10);
        AtomicBoolean retrying = new AtomicBoolean(false);

        Thread queryThread = new Thread(() -> {
            while (true) {
                if (!retrying.get()) {
                    try {
                        Thread.sleep(10000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    retrying.set(false);
                }

                System.out.println("Updating database...");
                AtomicInteger added = new AtomicInteger(0);
//                System.out.println("size = " + uniqueMemes.get().size());
                long start = System.currentTimeMillis();

                Set<Meme> removing;
                try {
                    removing = new HashSet<>(uniqueMemes.get());
                } catch (ConcurrentModificationException ignored) {
                    retrying.set(true);
                    continue;
                }

//                System.out.println("removing = " + removing);

                removing.forEach(meme -> {
                    added.incrementAndGet();
                    queryPool.execute(() -> {
                        try (Connection connection = DataSource.getConnection();
                             PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO `memes2` VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?);")) {
                            preparedStatement.setString(1, meme.getType());
                            preparedStatement.setString(2, meme.getSrc());
                            preparedStatement.setString(3, meme.getUrl());
                            preparedStatement.setString(4, meme.getLikes());
                            preparedStatement.setString(5, meme.getTags().size() > 0 ? meme.getTags().get(0) : null);
                            preparedStatement.setString(6, meme.getTags().size() > 1 ? meme.getTags().get(1) : null);
                            preparedStatement.setString(7, meme.getTags().size() > 2 ? meme.getTags().get(2) : null);
                            preparedStatement.setString(8, meme.getTags().size() > 3 ? meme.getTags().get(3) : null);
                            preparedStatement.setString(9, meme.getTags().size() > 4 ? meme.getTags().get(4) : null);
                            preparedStatement.executeUpdate();
                        } catch (SQLException ignored) {}
                        added.decrementAndGet();
                    });
                });

                removing.forEach(uniqueMemes.get()::remove);

                long startWhile = System.currentTimeMillis();

                while (added.get() > 0 || System.currentTimeMillis() - startWhile >= 30000) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }

                System.out.println("Finished updating database in " + (System.currentTimeMillis() - start) + "ms with " + removing.size() + " new unique entries");
//                System.out.println("size = " + uniqueMemes.get().size());
            }
        });

        queryThread.setName("QueryThread");
        queryThread.start();
    }

    private void startCollectingSets() throws IOException {
        try {
            Document document = Jsoup.connect("https://ifunny.co" + lastSet).get();
            String foundSet = document.getElementsByClass("feed__control").get(0).getElementsByTag("a").get(0).attr("href");
            if (foundSet == null) return;
            if (!seenSets.get().contains(foundSet)) {
                Queue<String> temp = sets.get();
                temp.add(foundSet);
                sets.set(temp);

//            System.out.println("foundSet = " + foundSet);
            }

            lastSet.set(foundSet);
        } catch (ConnectException e) {

        }
    }

    private void downloadSet(String set) throws IOException {
        try {
            if (!shuffle) {
                List<String> temp = seenSets.get();
                temp.add(set);
                seenSets.set(temp);
            }

            Document document = Jsoup.connect("https://ifunny.co" + set).header("Cache-control", "no-cache").header("Cache-store", "no-store").get();
            document.getElementsByClass("stream__item js-playlist-item").parallelStream().forEach(element -> {
                try {
//                    if (memes.get() > dontStartUntilReach) {
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
                        Meme meme = new Meme(type.name(), src, url, likes, tags);

                        if (!totalMemeCache.get().contains(meme)) {
                            totalMemeCache.get().add(meme);
                            uniqueMemes.get().add(meme);
                        } else {
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
//                    } else {
//                        duplicates.incrementAndGet();
//                    }

                    memes.incrementAndGet();
                } catch (Exception ignored) {
//                    ignored.printStackTrace();
                }
            });
//
//            downloadSet(document.getElementsByClass("feed__control").get(0).getElementsByTag("a").get(0).attr("href"));
        } catch (ConnectException ignored) {

            System.err.println("Timed out");
        }
    }

}
