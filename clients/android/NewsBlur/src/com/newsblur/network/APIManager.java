package com.newsblur.network;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.newsblur.domain.Classifier;
import com.newsblur.domain.Feed;
import com.newsblur.domain.FeedResult;
import com.newsblur.domain.Story;
import com.newsblur.domain.ValueMultimap;
import com.newsblur.network.domain.FeedFolderResponse;
import com.newsblur.network.domain.NewsBlurResponse;
import com.newsblur.network.domain.ProfileResponse;
import com.newsblur.network.domain.RegisterResponse;
import com.newsblur.network.domain.StoriesResponse;
import com.newsblur.network.domain.StoryTextResponse;
import com.newsblur.network.domain.UnreadCountResponse;
import com.newsblur.network.domain.UnreadStoryHashesResponse;
import com.newsblur.serialization.BooleanTypeAdapter;
import com.newsblur.serialization.ClassifierMapTypeAdapter;
import com.newsblur.serialization.DateStringTypeAdapter;
import com.newsblur.serialization.FeedListTypeAdapter;
import com.newsblur.serialization.StoryTypeAdapter;
import com.newsblur.util.AppConstants;
import com.newsblur.util.FeedSet;
import com.newsblur.util.NetworkUtils;
import com.newsblur.util.PrefConstants;
import com.newsblur.util.PrefsUtils;
import com.newsblur.util.ReadFilter;
import com.newsblur.util.StoryOrder;
import com.squareup.okhttp.FormEncodingBuilder;
import com.squareup.okhttp.OkHttpClient;
import com.squareup.okhttp.Request;
import com.squareup.okhttp.RequestBody;

import org.apache.http.HttpStatus;

public class APIManager {

	private Context context;
	private Gson gson;
    private String customUserAgent;
	private OkHttpClient httpClient;

	public APIManager(final Context context) {
		this.context = context;

        this.gson = new GsonBuilder()
                .registerTypeAdapter(Date.class, new DateStringTypeAdapter())
                .registerTypeAdapter(Boolean.class, new BooleanTypeAdapter())
                .registerTypeAdapter(boolean.class, new BooleanTypeAdapter())
                .registerTypeAdapter(Story.class, new StoryTypeAdapter())
                .registerTypeAdapter(new TypeToken<List<Feed>>(){}.getType(), new FeedListTypeAdapter())
                .registerTypeAdapter(new TypeToken<Map<String,Classifier>>(){}.getType(), new ClassifierMapTypeAdapter())
                .create();

        String appVersion = context.getSharedPreferences(PrefConstants.PREFERENCES, 0).getString(AppConstants.LAST_APP_VERSION, "unknown_version");
        this.customUserAgent =  "NewsBlur Android app" +
                                " (" + Build.MANUFACTURER + " " +
                                Build.MODEL + " " +
                                Build.VERSION.RELEASE + " " +
                                appVersion + ")";

		this.httpClient = new OkHttpClient();
	}

	public NewsBlurResponse login(final String username, final String password) {
        // This call should be pretty rare, but is expensive on the server side.  Log it
        // at an above-debug level so it will be noticed if it ever gets called too often.
        Log.i(this.getClass().getName(), "calling login API");
		final ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_USERNAME, username);
		values.put(APIConstants.PARAMETER_PASSWORD, password);
		final APIResponse response = post(APIConstants.URL_LOGIN, values);
        NewsBlurResponse loginResponse = response.getResponse(gson);
		if (!response.isError()) {
			PrefsUtils.saveLogin(context, username, response.getCookie());
		} 
        return loginResponse;
    }

    public boolean loginAs(final String username) {
        final ContentValues values = new ContentValues();
        values.put(APIConstants.PARAMETER_USER, username);
        String urlString = APIConstants.URL_LOGINAS + "?" + builderGetParametersString(values);
        final APIResponse response = get_single(urlString, HttpStatus.SC_MOVED_TEMPORARILY);
        if (!response.isError()) {
            PrefsUtils.saveLogin(context, username, response.getCookie());
            return true;
        } else {
            return false;
        }
    }

	public boolean setAutoFollow(boolean autofollow) {
		ContentValues values = new ContentValues();
		values.put("autofollow_friends", autofollow ? "true" : "false");
		final APIResponse response = post(APIConstants.URL_AUTOFOLLOW_PREF, values);
		return (!response.isError());
	}

	public NewsBlurResponse markFeedsAsRead(FeedSet fs, Long includeOlder, Long includeNewer) {
		ValueMultimap values = new ValueMultimap();

        if (fs.getSingleFeed() != null) {
            values.put(APIConstants.PARAMETER_FEEDID, fs.getSingleFeed());
        } else if (fs.getMultipleFeeds() != null) {
            for (String feedId : fs.getMultipleFeeds()) values.put(APIConstants.PARAMETER_FEEDID, feedId);
        } else if (fs.getSingleSocialFeed() != null) {
            values.put(APIConstants.PARAMETER_FEEDID, APIConstants.VALUE_PREFIX_SOCIAL + fs.getSingleSocialFeed().getKey());
        } else if (fs.getMultipleSocialFeeds() != null) {
            for (Map.Entry<String,String> entry : fs.getMultipleSocialFeeds().entrySet()) {
                values.put(APIConstants.PARAMETER_FEEDID, APIConstants.VALUE_PREFIX_SOCIAL + entry.getKey());
            }
        } else if (fs.isAllNormal()) {
            // all stories uses a special API call
            return markAllAsRead();
        } else if (fs.isAllSocial()) {
            values.put(APIConstants.PARAMETER_FEEDID, APIConstants.VALUE_ALLSOCIAL);
        } else {
            throw new IllegalStateException("Asked to get stories for FeedSet of unknown type.");
        }

        if (includeOlder != null) {
            // the app uses  milliseconds but the API wants seconds
            long cut = includeOlder.longValue();
            values.put(APIConstants.PARAMETER_CUTOFF_TIME, Long.toString(cut/1000L));
            values.put(APIConstants.PARAMETER_DIRECTION, APIConstants.VALUE_OLDER);
        }
        if (includeNewer != null) {
            // the app uses  milliseconds but the API wants seconds
            long cut = includeNewer.longValue();
            values.put(APIConstants.PARAMETER_CUTOFF_TIME, Long.toString(cut/1000L));
            values.put(APIConstants.PARAMETER_DIRECTION, APIConstants.VALUE_NEWER);
        }

		APIResponse response = post(APIConstants.URL_MARK_FEED_AS_READ, values);
        // TODO: these calls use a different return format than others: the errors field is an array, not an object
        //return response.getResponse(gson, NewsBlurResponse.class);
        NewsBlurResponse nbr = new NewsBlurResponse();
        if (response.isError()) nbr.message = "err";
        return nbr;
	}
	
	private NewsBlurResponse markAllAsRead() {
		ValueMultimap values = new ValueMultimap();
		values.put(APIConstants.PARAMETER_DAYS, "0");
		APIResponse response = post(APIConstants.URL_MARK_ALL_AS_READ, values);
        // TODO: these calls use a different return format than others: the errors field is an array, not an object
        //return response.getResponse(gson, NewsBlurResponse.class);
        NewsBlurResponse nbr = new NewsBlurResponse();
        if (response.isError()) nbr.message = "err";
        return nbr;
	}

    public NewsBlurResponse markStoryAsRead(String storyHash) {
        ValueMultimap values = new ValueMultimap();
        values.put(APIConstants.PARAMETER_STORY_HASH, storyHash);
        APIResponse response = post(APIConstants.URL_MARK_STORIES_READ, values);
        return response.getResponse(gson, NewsBlurResponse.class);
    }

	public NewsBlurResponse markStoryAsStarred(String storyHash) {
		ValueMultimap values = new ValueMultimap();
		values.put(APIConstants.PARAMETER_STORY_HASH, storyHash);
		APIResponse response = post(APIConstants.URL_MARK_STORY_AS_STARRED, values);
        return response.getResponse(gson, NewsBlurResponse.class);
	}
	
    public NewsBlurResponse markStoryAsUnstarred(String storyHash) {
		ValueMultimap values = new ValueMultimap();
		values.put(APIConstants.PARAMETER_STORY_HASH, storyHash);
		APIResponse response = post(APIConstants.URL_MARK_STORY_AS_UNSTARRED, values);
        return response.getResponse(gson, NewsBlurResponse.class);
	}

    public NewsBlurResponse markStoryHashUnread(String hash) {
		final ValueMultimap values = new ValueMultimap();
        values.put(APIConstants.PARAMETER_STORY_HASH, hash);
        APIResponse response = post(APIConstants.URL_MARK_STORY_HASH_UNREAD, values);
        return response.getResponse(gson, NewsBlurResponse.class);
    }

	public RegisterResponse signup(final String username, final String password, final String email) {
		final ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_USERNAME, username);
		values.put(APIConstants.PARAMETER_PASSWORD, password);
		values.put(APIConstants.PARAMETER_EMAIL, email);
		final APIResponse response = post(APIConstants.URL_SIGNUP, values);
        RegisterResponse registerResponse = ((RegisterResponse) response.getResponse(gson, RegisterResponse.class));
		if (!response.isError()) {
			PrefsUtils.saveLogin(context, username, response.getCookie());

			CookieSyncManager.createInstance(context.getApplicationContext());
			CookieManager cookieManager = CookieManager.getInstance();

			cookieManager.setCookie(APIConstants.COOKIE_DOMAIN, response.getCookie());
			CookieSyncManager.getInstance().sync();
		}
        return registerResponse;
	}

	public ProfileResponse updateUserProfile() {
		final APIResponse response = get(APIConstants.URL_MY_PROFILE);
		if (!response.isError()) {
			ProfileResponse profileResponse = (ProfileResponse) response.getResponse(gson, ProfileResponse.class);
			PrefsUtils.saveUserDetails(context, profileResponse.user);
			return profileResponse;
		} else {
			return null;
		}
	}

    public UnreadCountResponse getFeedUnreadCounts(Set<String> apiIds) {
        ValueMultimap values = new ValueMultimap();
        for (String id : apiIds) {
            values.put(APIConstants.PARAMETER_FEEDID, id);
        }
        APIResponse response = get(APIConstants.URL_FEED_UNREAD_COUNT, values);
        return (UnreadCountResponse) response.getResponse(gson, UnreadCountResponse.class);
    }

    public UnreadStoryHashesResponse getUnreadStoryHashes() {
		ValueMultimap values = new ValueMultimap();
        values.put(APIConstants.PARAMETER_INCLUDE_TIMESTAMPS, "1");
        APIResponse response = get(APIConstants.URL_UNREAD_HASHES, values);
        return (UnreadStoryHashesResponse) response.getResponse(gson, UnreadStoryHashesResponse.class);
    }

    public StoriesResponse getStoriesByHash(List<String> storyHashes) {
		ValueMultimap values = new ValueMultimap();
        for (String hash : storyHashes) {
            values.put(APIConstants.PARAMETER_H, hash);
        }
        values.put(APIConstants.PARAMETER_INCLUDE_HIDDEN, APIConstants.VALUE_TRUE);
        APIResponse response = get(APIConstants.URL_RIVER_STORIES, values);
        return (StoriesResponse) response.getResponse(gson, StoriesResponse.class);
    }

    /**
     * Fetches stories for the given FeedSet, choosing the correct API and the right
     * request parameters as needed.
     */
    public StoriesResponse getStories(FeedSet fs, int pageNumber, StoryOrder order, ReadFilter filter) {
        Uri uri = null;
        ValueMultimap values = new ValueMultimap();
    
        // create the URI and populate request params depending on what kind of stories we want
        if (fs.getSingleFeed() != null) {
            uri = Uri.parse(APIConstants.URL_FEED_STORIES).buildUpon().appendPath(fs.getSingleFeed()).build();
            values.put(APIConstants.PARAMETER_FEEDS, fs.getSingleFeed());
            values.put(APIConstants.PARAMETER_INCLUDE_HIDDEN, APIConstants.VALUE_TRUE);
        } else if (fs.getMultipleFeeds() != null) {
            uri = Uri.parse(APIConstants.URL_RIVER_STORIES);
            for (String feedId : fs.getMultipleFeeds()) values.put(APIConstants.PARAMETER_FEEDS, feedId);
            values.put(APIConstants.PARAMETER_INCLUDE_HIDDEN, APIConstants.VALUE_TRUE);
        } else if (fs.getSingleSocialFeed() != null) {
            String feedId = fs.getSingleSocialFeed().getKey();
            String username = fs.getSingleSocialFeed().getValue();
            uri = Uri.parse(APIConstants.URL_SOCIALFEED_STORIES).buildUpon().appendPath(feedId).appendPath(username).build();
            values.put(APIConstants.PARAMETER_USER_ID, feedId);
            values.put(APIConstants.PARAMETER_USERNAME, username);
        } else if (fs.getMultipleSocialFeeds() != null) {
            uri = Uri.parse(APIConstants.URL_SHARED_RIVER_STORIES);
            for (Map.Entry<String,String> entry : fs.getMultipleSocialFeeds().entrySet()) {
                values.put(APIConstants.PARAMETER_FEEDS, entry.getKey());
            }
        } else if (fs.isAllNormal()) {
            uri = Uri.parse(APIConstants.URL_RIVER_STORIES);
            values.put(APIConstants.PARAMETER_INCLUDE_HIDDEN, APIConstants.VALUE_TRUE);
        } else if (fs.isAllSocial()) {
            uri = Uri.parse(APIConstants.URL_SHARED_RIVER_STORIES);
        } else if (fs.isAllRead()) {
            uri = Uri.parse(APIConstants.URL_READ_STORIES);
        } else if (fs.isAllSaved()) {
            uri = Uri.parse(APIConstants.URL_STARRED_STORIES);
        } else if (fs.isGlobalShared()) {
            uri = Uri.parse(APIConstants.URL_SHARED_RIVER_STORIES);
            values.put(APIConstants.PARAMETER_GLOBAL_FEED, Boolean.TRUE.toString());
        } else {
            throw new IllegalStateException("Asked to get stories for FeedSet of unknown type.");
        }

		// request params common to most story sets
        values.put(APIConstants.PARAMETER_PAGE_NUMBER, Integer.toString(pageNumber));
        if ( !(fs.isAllRead() || fs.isAllSaved())) {
		    values.put(APIConstants.PARAMETER_ORDER, order.getParameterValue());
		    values.put(APIConstants.PARAMETER_READ_FILTER, filter.getParameterValue());
        }

		APIResponse response = get(uri.toString(), values);
        return (StoriesResponse) response.getResponse(gson, StoriesResponse.class);
    }

	public boolean followUser(final String userId) {
		final ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_USERID, userId);
		final APIResponse response = post(APIConstants.URL_FOLLOW, values);
		if (!response.isError()) {
			return true;
		} else {
			return false;
		}
	}

	public boolean unfollowUser(final String userId) {
		final ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_USERID, userId);
		final APIResponse response = post(APIConstants.URL_UNFOLLOW, values);
		if (!response.isError()) {
			return true;
		} else {
			return false;
		}
	}

	public Boolean shareStory(final String storyId, final String feedId, final String comment, final String sourceUserId) {
		final ContentValues values = new ContentValues();
		if (!TextUtils.isEmpty(comment)) {
			values.put(APIConstants.PARAMETER_SHARE_COMMENT, comment);
		}
		if (!TextUtils.isEmpty(sourceUserId)) {
			values.put(APIConstants.PARAMETER_SHARE_SOURCEID, sourceUserId);
		}
		values.put(APIConstants.PARAMETER_FEEDID, feedId);
		values.put(APIConstants.PARAMETER_STORYID, storyId);

		final APIResponse response = post(APIConstants.URL_SHARE_STORY, values);
		if (!response.isError()) {
			return true;
		} else {
			return false;
		}
	}

	/**
     * Fetch the list of feeds/folders/socials from the backend.
     * 
     * @param doUpdateCounts forces a refresh of unread counts.  This has a high latency
     *        cost and should not be set if the call is being used to display the UI for
     *        the first time, in which case it is more appropriate to make a separate,
     *        additional call to refreshFeedCounts().
     */
    public FeedFolderResponse getFolderFeedMapping(boolean doUpdateCounts) {
		ContentValues params = new ContentValues();
		params.put(APIConstants.PARAMETER_UPDATE_COUNTS, (doUpdateCounts ? "true" : "false"));
		APIResponse response = get(APIConstants.URL_FEEDS, params);

		if (response.isError()) {
            Log.e(this.getClass().getName(), "Error fetching feeds: " + response.getErrorMessage());
            return null;
        }

		// note: this response is complex enough, we have to do a custom parse in the FFR
        FeedFolderResponse result = new FeedFolderResponse(response.getResponseBody(), gson);
        result.readTime = response.readTime;
        return result;
	}

	public NewsBlurResponse trainClassifier(String feedId, String key, int type, int action) {
		String typeText = null;
		String actionText = null;

		switch (type) {
		case Classifier.AUTHOR:
			typeText = "author"; 
			break;
		case Classifier.TAG:
			typeText = "tag";
			break;
		case Classifier.TITLE:
			typeText = "title";
			break;
		case Classifier.FEED:
			typeText = "feed";
			break;	
		}

		switch (action) {
		case Classifier.CLEAR_LIKE:
			actionText = "remove_like_"; 
			break;
		case Classifier.CLEAR_DISLIKE:
			actionText = "remove_dislike_"; 
			break;	
		case Classifier.LIKE:
			actionText = "like_";
			break;
		case Classifier.DISLIKE:
			actionText = "dislike_";
			break;	
		}

		StringBuilder builder = new StringBuilder();;
		builder.append(actionText);
		builder.append(typeText);

		ContentValues values = new ContentValues();
		if (type == Classifier.FEED) {
			values.put(builder.toString(), feedId);
		} else {
			values.put(builder.toString(), key);
		}
		values.put(APIConstants.PARAMETER_FEEDID, feedId);

		final APIResponse response = post(APIConstants.URL_CLASSIFIER_SAVE, values);
		return response.getResponse(gson, NewsBlurResponse.class);
	}

	public ProfileResponse getUser(String userId) {
		final ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_USER_ID, userId);
		final APIResponse response = get(APIConstants.URL_USER_PROFILE, values);
		if (!response.isError()) {
			ProfileResponse profileResponse = (ProfileResponse) response.getResponse(gson, ProfileResponse.class);
			return profileResponse;
		} else {
			return null;
		}
	}

	public StoryTextResponse getStoryText(String feedId, String storyId) {
		final ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_FEEDID, feedId);
		values.put(APIConstants.PARAMETER_STORYID, storyId);
		final APIResponse response = get(APIConstants.URL_STORY_TEXT, values);
		if (!response.isError()) {
			StoryTextResponse storyTextResponse = (StoryTextResponse) response.getResponse(gson, StoryTextResponse.class);
			return storyTextResponse;
		} else {
			return null;
		}
	}

	public boolean favouriteComment(String storyId, String commentId, String feedId) {
		ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_STORYID, storyId);
		values.put(APIConstants.PARAMETER_STORY_FEEDID, feedId);
		values.put(APIConstants.PARAMETER_COMMENT_USERID, commentId);
		final APIResponse response = post(APIConstants.URL_LIKE_COMMENT, values);
		return (!response.isError());
	}

	public Boolean unFavouriteComment(String storyId, String commentId, String feedId) {
		ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_STORYID, storyId);
		values.put(APIConstants.PARAMETER_STORY_FEEDID, feedId);
		values.put(APIConstants.PARAMETER_COMMENT_USERID, commentId);
		final APIResponse response = post(APIConstants.URL_UNLIKE_COMMENT, values);
		return (!response.isError());
	}

	public boolean replyToComment(String storyId, String storyFeedId, String commentUserId, String reply) {
		ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_STORYID, storyId);
		values.put(APIConstants.PARAMETER_STORY_FEEDID, storyFeedId);
		values.put(APIConstants.PARAMETER_COMMENT_USERID, commentUserId);
		values.put(APIConstants.PARAMETER_REPLY_TEXT, reply);
		final APIResponse response = post(APIConstants.URL_REPLY_TO, values);
		return (!response.isError());
	}

	public boolean addFeed(String feedUrl, String folderName) {
		ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_URL, feedUrl);
		if (!TextUtils.isEmpty(folderName)) {
			values.put(APIConstants.PARAMETER_FOLDER, folderName);
		}
		final APIResponse response = post(APIConstants.URL_ADD_FEED, values);
		return (!response.isError());
	}

	public FeedResult[] searchForFeed(String searchTerm) {
		ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_FEED_SEARCH_TERM, searchTerm);
		final APIResponse response = get(APIConstants.URL_FEED_AUTOCOMPLETE, values);

		if (!response.isError()) {
            return gson.fromJson(response.getResponseBody(), FeedResult[].class);
		} else {
			return null;
		}
	}

	public NewsBlurResponse deleteFeed(String feedId, String folderName) {
		ContentValues values = new ContentValues();
		values.put(APIConstants.PARAMETER_FEEDID, feedId);
		if ((!TextUtils.isEmpty(folderName)) && (!folderName.equals(AppConstants.ROOT_FOLDER))) {
			values.put(APIConstants.PARAMETER_IN_FOLDER, folderName);
		}
		APIResponse response = post(APIConstants.URL_DELETE_FEED, values);
		return response.getResponse(gson, NewsBlurResponse.class);
	}

    /* HTTP METHODS */
   
	private APIResponse get(final String urlString) {
        APIResponse response;
        int tryCount = 0;
        do {
            backoffSleep(tryCount++);
            response = get_single(urlString, HttpStatus.SC_OK);
        } while ((response.isError()) && (tryCount < AppConstants.MAX_API_TRIES));
        return response;
    }

	private APIResponse get_single(final String urlString, int expectedReturnCode) {
		if (!NetworkUtils.isOnline(context)) {
			return new APIResponse(context);
		}

		if (AppConstants.VERBOSE_LOG) {
			Log.d(this.getClass().getName(), "API GET " + urlString);
		}

		Request.Builder requestBuilder = new Request.Builder().url(urlString);
		addCookieHeader(requestBuilder);
		requestBuilder.header("User-Agent", this.customUserAgent);

		return new APIResponse(context, httpClient, requestBuilder.build(), expectedReturnCode);
	}

	private void addCookieHeader(Request.Builder requestBuilder) {
		SharedPreferences preferences = context.getSharedPreferences(PrefConstants.PREFERENCES, 0);
		String cookie = preferences.getString(PrefConstants.PREF_COOKIE, null);
		if (cookie != null) {
			requestBuilder.header("Cookie", cookie);
		}
	}

	private APIResponse get(final String urlString, final ContentValues values) {
        return this.get(urlString + "?" + builderGetParametersString(values));
	}

    private String builderGetParametersString(ContentValues values) {
        List<String> parameters = new ArrayList<String>();
        for (Entry<String, Object> entry : values.valueSet()) {
            StringBuilder builder = new StringBuilder();
            builder.append((String) entry.getKey());
            builder.append("=");
            builder.append(URLEncoder.encode((String) entry.getValue()));
            parameters.add(builder.toString());
        }
        return TextUtils.join("&", parameters);
    }
	
	private APIResponse get(final String urlString, final ValueMultimap valueMap) {
        return this.get(urlString + "?" + valueMap.getParameterString());
	}

	private APIResponse post(String urlString, RequestBody formBody) {
        APIResponse response;
        int tryCount = 0;
        do {
            backoffSleep(tryCount++);
            response = post_single(urlString, formBody);
        } while ((response.isError()) && (tryCount < AppConstants.MAX_API_TRIES));
        return response;
    }

	private APIResponse post_single(String urlString, RequestBody formBody) {
		if (!NetworkUtils.isOnline(context)) {
			return new APIResponse(context);
		}

		if (AppConstants.VERBOSE_LOG) {
			Log.d(this.getClass().getName(), "API POST " + urlString);
			Log.d(this.getClass().getName(), "post body: " + formBody.toString());
		}

		Request.Builder requestBuilder = new Request.Builder().url(urlString);
		addCookieHeader(requestBuilder);
		requestBuilder.post(formBody);

		return new APIResponse(context, httpClient, requestBuilder.build());
	}

	private APIResponse post(final String urlString, final ContentValues values) {
		FormEncodingBuilder formEncodingBuilder = new FormEncodingBuilder();
		for (Entry<String, Object> entry : values.valueSet()) {
			formEncodingBuilder.add(entry.getKey(), (String)entry.getValue());
		}
        return this.post(urlString, formEncodingBuilder.build());
	}
	
	private APIResponse post(final String urlString, final ValueMultimap valueMap) {
        return this.post(urlString, valueMap.asFormEncodedRequestBody());
	}

    /**
     * Pause for the sake of exponential retry-backoff as apropriate before the Nth call as counted
     * by the zero-indexed tryCount.
     */
    private void backoffSleep(int tryCount) {
        if (tryCount == 0) return;
        Log.i(this.getClass().getName(), "API call failed, pausing before retry number " + tryCount);
        try {
            // simply double the base sleep time for each subsequent try
            long factor = Math.round(Math.pow(2.0d, tryCount));
            Thread.sleep(AppConstants.API_BACKOFF_BASE_MILLIS * factor);
        } catch (InterruptedException ie) {
            Log.w(this.getClass().getName(), "Abandoning API backoff due to interrupt.");
        }
    }

}
