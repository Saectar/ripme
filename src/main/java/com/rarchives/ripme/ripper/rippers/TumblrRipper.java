package com.rarchives.ripme.ripper.rippers;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import com.rarchives.ripme.ripper.AbstractRipper;
import com.rarchives.ripme.utils.Utils;

public class TumblrRipper extends AbstractRipper {

    private static final String DOMAIN = "tumblr.com",
                                HOST   = "tumblr";
    private static final Logger logger = Logger.getLogger(TumblrRipper.class);
    
    private enum ALBUM_TYPE {
        SUBDOMAIN,
        TAG,
        POST
    }
    private ALBUM_TYPE albumType;
    private String subdomain, tagName, postNumber;
    
    private final String API_KEY;

    public TumblrRipper(URL url) throws IOException {
        super(url);
        API_KEY = Utils.getConfigString("tumblr.auth", null);
        if (API_KEY == null) {
            throw new IOException("Could not find tumblr authentication key in configuration");
        }
    }

    @Override
    public boolean canRip(URL url) {
        return url.getHost().endsWith(DOMAIN);
    }

    @Override
    public URL sanitizeURL(URL url) throws MalformedURLException {
        return url;
    }

    @Override
    public void rip() throws IOException {
        String[] mediaTypes;
        if (albumType == ALBUM_TYPE.POST) {
            mediaTypes = new String[] { "post" };
        } else {
            mediaTypes = new String[] { "photo", "video" };
        }
        int offset;
        for (String mediaType : mediaTypes) {
            offset = 0;
            while (true) {
                String apiURL = getTumblrApiURL(mediaType, offset);
                logger.info("   Retrieving " + apiURL);
                Document doc = Jsoup.connect(apiURL)
                                    .ignoreContentType(true)
                                    .header("User-agent", USER_AGENT)
                                    .get();
                String jsonString = doc.body().html().replaceAll("&quot;", "\"");
                if (!handleJSON(jsonString)) {
                    // Returns false if an error occurs and we should stop.
                    break;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.error("[!] Exception while waiting to load next album:", e);
                    break;
                }
                offset += 20;
            }
        }
        waitForThreads();
    }
    
    private boolean handleJSON(String jsonString) {
        JSONObject json = new JSONObject(jsonString);
        if (json == null || !json.has("response")) {
            logger.error("[!] JSON response from tumblr was invalid: " + jsonString);
            return false;
        }
        JSONArray posts, photos;
        JSONObject post, photo;
        URL fileURL;

        posts = json.getJSONObject("response").getJSONArray("posts");
        if (posts.length() == 0) {
            logger.info("   Zero posts returned. Dropping out.");
            return false;
        }

        for (int i = 0; i < posts.length(); i++) {
            post = posts.getJSONObject(i);
            if (post.has("photos")) {
                photos = post.getJSONArray("photos");
                for (int j = 0; j < photos.length(); j++) {
                    photo = photos.getJSONObject(j);
                    try {
                        fileURL = new URL(photo.getJSONObject("original_size").getString("url"));
                        addURLToDownload(fileURL);
                    } catch (Exception e) {
                        logger.error("[!] Error while parsing photo in " + photo, e);
                        continue;
                    }
                }
            } else if (post.has("video_url")) {
                try {
                    fileURL = new URL(post.getString("video_url"));
                    addURLToDownload(fileURL);
                } catch (Exception e) {
                        logger.error("[!] Error while parsing video in " + post, e);
                        return true;
                }
            }
            if (albumType == ALBUM_TYPE.POST) {
                return false;
            }
        }
        return true;
    }
    
    private String getTumblrApiURL(String mediaType, int offset) {
        StringBuilder sb = new StringBuilder();
        if (albumType == ALBUM_TYPE.POST) { 
            sb.append("http://api.tumblr.com/v2/blog/")
              .append(subdomain)
              .append(".tumblr.com/posts?id=")
              .append(postNumber)
              .append("&api_key=")
              .append(API_KEY);
            return sb.toString();
        }
        sb.append("http://api.tumblr.com/v2/blog/")
          .append(subdomain)
          .append(".tumblr.com/posts/")
          .append(mediaType)
          .append("?api_key=")
          .append(API_KEY)
          .append("&offset=")
          .append(offset);
        if (albumType == ALBUM_TYPE.TAG) {
           sb.append("&tag=")
             .append(tagName);
        }
        return sb.toString();
    }

    @Override
    public String getHost() {
        return HOST;
    }

    @Override
    public String getGID(URL url) throws MalformedURLException {
        Pattern p;
        Matcher m;
        // Tagged URL
        p = Pattern.compile("^https?://([a-zA-Z0-9\\-]{1,})\\.tumblr\\.com/tagged/([a-zA-Z0-9\\-]{1,}).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.TAG;
            this.subdomain = m.group(1);
            this.tagName = m.group(2);
            this.tagName = this.tagName.replace('-', '+').replace("_", "%20");
            return this.subdomain + "_tag_" + this.tagName;
        }
        // Post URL
        p = Pattern.compile("^https?://([a-zA-Z0-9\\-]{1,})\\.tumblr\\.com/post/([0-9]{1,}).*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.POST;
            this.subdomain = m.group(1);
            this.postNumber = m.group(2);
            return this.subdomain + "_post_" + this.postNumber;
        }
        // Subdomain-level URL
        p = Pattern.compile("^https?://([a-zA-Z0-9\\-]{1,})\\.tumblr\\.com/?.*$");
        m = p.matcher(url.toExternalForm());
        if (m.matches()) {
            this.albumType = ALBUM_TYPE.SUBDOMAIN;
            this.subdomain = m.group(1);
            return this.subdomain;
        }
        // TODO support non-tumblr.com domains
        throw new MalformedURLException("Expected format: http://user.tumblr.com[/tagged/tag|/post/postno]");
    }

}