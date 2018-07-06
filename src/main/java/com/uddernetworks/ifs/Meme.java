package com.uddernetworks.ifs;

import java.util.List;

public class Meme {
    private String type;
    private String src;
    private String url;
    private String likes;
    private List<String> tags;

    public Meme(String src) {
        this.src = src;
    }

    public Meme(String type, String src, String url, String likes, List<String> tags) {
        this.type = type;
        this.src = src;
        this.url = url;
        this.likes = likes;
        this.tags = tags;
    }

    public String getType() {
        return type;
    }

    public String getSrc() {
        return src;
    }

    public String getUrl() {
        return url;
    }

    public String getLikes() {
        return likes;
    }

    public List<String> getTags() {
        return tags;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Meme && ((Meme) obj).getSrc().equals(src);
    }

    @Override
    public int hashCode() {
        return src.hashCode();
    }
}
