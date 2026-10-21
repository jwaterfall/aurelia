package app.aurelia.tv.jellyfin;

import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The signed-in user's films and shows: whole libraries sorted and filtered as Jellyfin's own apps offer, a show's
 * seasons and episodes, and marking things watched or favourite. Every call blocks; run them with
 * {@link Api#async}.
 */
public final class Catalog {

    public enum Kind { Movie, Series }

    /** How a library is sorted, as Jellyfin names it, with the order that reads naturally first. */
    public enum Sort {
        Name("SortName", true),
        DateAdded("DateCreated", false),
        Released("PremiereDate", false),
        Rating("CommunityRating", false),
        Runtime("Runtime", true),
        Random("Random", true);

        final String field;
        /** Ascending reads naturally (A to Z, shortest first); the others start with the newest or highest. */
        public final boolean ascendingFirst;

        Sort(String field, boolean ascendingFirst) {
            this.field = field;
            this.ascendingFirst = ascendingFirst;
        }
    }

    public enum Watched { Any, Unwatched, Watched }

    /** What to show of a library. */
    public static final class Query {
        public Sort sort = Sort.DateAdded;
        public boolean ascending;
        public Watched watched = Watched.Any;
        public boolean favourites;
        /** Empty for every genre. */
        public String genre = "";
    }

    /** A film, show, season or episode: what the pages show of it. */
    /** Someone in a film's or show's credits. */
    public static final class Person {
        public final String id;
        public final String name;
        /** The character, for an actor; else empty. */
        public final String role;
        /** "Actor", "Director", "Writer", "Producer"... */
        public final String type;
        public final String imageTag;

        Person(JSONObject o) {
            id = o.optString("Id", "");
            name = o.optString("Name", "");
            role = o.optString("Role", "");
            type = o.optString("Type", "");
            imageTag = o.optString("PrimaryImageTag", "");
        }
    }

    public static final class Item {
        public final String id;
        public final String type;
        public final String name;
        public final String overview;
        public final int year;
        /** Last year of a show that has ended, else 0. */
        public final int endYear;
        public final boolean continuing;
        public final String officialRating;
        public final float communityRating;
        public final int criticRating;
        public final long runtimeMs;
        public final List<String> genres;
        public final boolean played;
        public final boolean favourite;
        public final long positionMs;
        /** Episodes of a show (or season) not yet watched. */
        public final int unplayed;
        public final int index;
        public final int parentIndex;
        public final String seriesId;
        /** An episode's show. */
        public final String seriesName;
        public final String seasonId;
        public final String primaryTag;
        public final String logoId;
        public final String logoTag;
        public final String backdropId;
        public final String backdropTag;
        public final String thumbId;
        public final String thumbTag;
        public final int childCount;
        /** Director first, then up to a few actors; filled only by {@link #item}. */
        public final List<String> directors;
        public final List<String> cast;
        /** Everyone credited, in the server's order (cast first). */
        public final List<Person> people;
        /** A person's birthplace. */
        public final String birthplace;
        /** A person's birth date (yyyy-MM-dd), or empty. */
        public final String born;

        Item(JSONObject o) {
            id = o.optString("Id", "");
            type = o.optString("Type", "");
            name = o.optString("Name", "");
            overview = o.optString("Overview", "").trim();
            year = o.optInt("ProductionYear", 0);
            String end = o.optString("EndDate", "");
            endYear = end.length() >= 4 ? parseInt(end.substring(0, 4)) : 0;
            continuing = "Continuing".equals(o.optString("Status", ""));
            officialRating = o.optString("OfficialRating", "");
            communityRating = (float) o.optDouble("CommunityRating", 0);
            criticRating = o.optInt("CriticRating", 0);
            runtimeMs = o.optLong("RunTimeTicks", 0) / 10_000L;
            genres = strings(o.optJSONArray("Genres"));
            JSONObject user = o.optJSONObject("UserData");
            played = user != null && user.optBoolean("Played", false);
            favourite = user != null && user.optBoolean("IsFavorite", false);
            positionMs = user != null ? user.optLong("PlaybackPositionTicks", 0) / 10_000L : 0;
            unplayed = user != null ? user.optInt("UnplayedItemCount", 0) : 0;
            index = o.optInt("IndexNumber", 0);
            parentIndex = o.optInt("ParentIndexNumber", 0);
            seriesId = o.optString("SeriesId", "");
            seriesName = o.optString("SeriesName", "");
            seasonId = o.optString("SeasonId", "");
            JSONObject tags = o.optJSONObject("ImageTags");
            primaryTag = tags != null ? tags.optString("Primary", "") : "";
            String ownLogo = tags != null ? tags.optString("Logo", "") : "";
            logoId = !ownLogo.isEmpty() ? id : o.optString("ParentLogoItemId", "");
            logoTag = !ownLogo.isEmpty() ? ownLogo : o.optString("ParentLogoImageTag", "");
            JSONArray backdrops = o.optJSONArray("BackdropImageTags");
            JSONArray parentBackdrops = o.optJSONArray("ParentBackdropImageTags");
            if (backdrops != null && backdrops.length() > 0) {
                backdropId = id;
                backdropTag = backdrops.optString(0);
            } else if (parentBackdrops != null && parentBackdrops.length() > 0) {
                backdropId = o.optString("ParentBackdropItemId", "");
                backdropTag = parentBackdrops.optString(0);
            } else {
                backdropId = "";
                backdropTag = "";
            }
            String ownThumb = tags != null ? tags.optString("Thumb", "") : "";
            thumbId = !ownThumb.isEmpty() ? id : o.optString("ParentThumbItemId", "");
            thumbTag = !ownThumb.isEmpty() ? ownThumb : o.optString("ParentThumbImageTag", "");
            childCount = o.optInt("ChildCount", 0);
            List<String> d = new ArrayList<>();
            List<String> c = new ArrayList<>();
            List<Person> all = new ArrayList<>();
            JSONArray people = o.optJSONArray("People");
            for (int i = 0; people != null && i < people.length(); i++) {
                JSONObject p = people.optJSONObject(i);
                if (p == null) continue;
                if (!p.optString("Id", "").isEmpty()) all.add(new Person(p));
                if ("Director".equals(p.optString("Type"))) d.add(p.optString("Name"));
                else if ("Actor".equals(p.optString("Type")) && c.size() < 3) c.add(p.optString("Name"));
            }
            directors = d;
            cast = c;
            this.people = all;
            JSONArray places = o.optJSONArray("ProductionLocations");
            birthplace = places != null && places.length() > 0 ? places.optString(0) : "";
            String premiere = o.optString("PremiereDate", "");
            born = "Person".equals(type) && premiere.length() >= 10 ? premiere.substring(0, 10) : "";
        }

        public boolean isSeries() {
            return "Series".equals(type);
        }

        /** Started and not finished. */
        public boolean inProgress() {
            return positionMs > 0 && !played;
        }

        public int progressPercent() {
            return runtimeMs > 0 ? (int) Math.min(100, 100 * positionMs / runtimeMs) : 0;
        }
    }

    /** A page of a library, and how many there are in all. */
    public static final class Page {
        public final List<Item> items;
        public final int total;

        Page(List<Item> items, int total) {
            this.items = items;
            this.total = total;
        }
    }

    private static final String FIELDS = "Overview,Genres,ProductionYear,OfficialRating,CommunityRating,"
            + "CriticRating,EndDate,Status,ChildCount,ParentId";
    /** Fetched at a time while a library scrolls. */
    public static final int PAGE = 120;

    private Catalog() { }

    /** The id of the user's first library of {@code kind}, or empty when they have none. */
    public static String library(Account account, Kind kind) throws IOException {
        JSONObject views = Api.getJson(account.server() + "/UserViews?userId=" + account.userId(), Library.auth(account));
        JSONArray items = views.optJSONArray("Items");
        String type = kind == Kind.Movie ? "movies" : "tvshows";
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject v = items.optJSONObject(i);
            if (v != null && type.equals(v.optString("CollectionType"))) return v.optString("Id");
        }
        return "";
    }

    /** {@code limit} items of {@code kind} from {@code start}, sorted and filtered as {@code q} says. */
    public static Page items(Account account, String libraryId, Kind kind, Query q, int start, int limit)
            throws IOException {
        StringBuilder url = new StringBuilder(account.server()).append("/Users/").append(account.userId())
                .append("/Items?recursive=true&includeItemTypes=").append(kind.name())
                .append("&fields=").append(FIELDS)
                .append("&enableImageTypes=Primary,Backdrop,Logo,Thumb&imageTypeLimit=1")
                .append("&startIndex=").append(start).append("&limit=").append(limit)
                .append("&sortBy=").append(q.sort.field);
        if (q.sort != Sort.Name) url.append(",SortName");
        url.append("&sortOrder=").append(q.ascending ? "Ascending" : "Descending");
        if (!libraryId.isEmpty()) url.append("&parentId=").append(libraryId);
        List<String> filters = new ArrayList<>();
        if (q.watched == Watched.Unwatched) filters.add("IsUnplayed");
        if (q.watched == Watched.Watched) filters.add("IsPlayed");
        if (q.favourites) filters.add("IsFavorite");
        if (!filters.isEmpty()) url.append("&filters=").append(String.join(",", filters));
        if (!q.genre.isEmpty()) url.append("&genres=").append(Uri.encode(q.genre));
        JSONObject page = Api.getJson(url.toString(), Library.auth(account));
        return new Page(items(page), page.optInt("TotalRecordCount", 0));
    }

    /**
     * Up to {@code limit} of {@code types} (Jellyfin's item types: "Movie", "Series", "Episode") whose names
     * match {@code term}, best matches first.
     */
    public static List<Item> search(Account account, String term, String types, int limit) throws IOException {
        JSONObject page = Api.getJson(account.server() + "/Users/" + account.userId()
                + "/Items?recursive=true&searchTerm=" + Uri.encode(term) + "&includeItemTypes=" + types
                + "&fields=" + FIELDS + "&enableImageTypes=Primary,Backdrop,Logo,Thumb&imageTypeLimit=1"
                + "&limit=" + limit, Library.auth(account));
        return items(page);
    }

    /** Which collection (box set) each film is in, for one user; built once, and again after a while. */
    private static final Map<String, Item> collections = new HashMap<>();
    private static String collectionsUser = "";
    private static long collectionsAt;
    private static final long COLLECTIONS_MS = 10 * 60_000L;

    /**
     * The collection {@code id} is in (Jellyfin's box sets: "The Matrix Collection"), or null. The server has no
     * way to ask this of a film, so the first call in a while reads every collection's contents; a library has
     * few. Blocks; call it in the background.
     */
    public static Item collectionOf(Account account, String id) throws IOException {
        synchronized (collections) {
            long now = System.currentTimeMillis();
            if (!account.userId().equals(collectionsUser) || now - collectionsAt > COLLECTIONS_MS) {
                collections.clear();
                JSONObject sets = Api.getJson(account.server() + "/Users/" + account.userId()
                        + "/Items?recursive=true&includeItemTypes=BoxSet&fields=" + FIELDS, Library.auth(account));
                for (Item set : items(sets)) {
                    JSONObject kids = Api.getJson(account.server() + "/Users/" + account.userId()
                            + "/Items?parentId=" + set.id, Library.auth(account));
                    for (Item kid : items(kids)) collections.put(kid.id, set);
                }
                collectionsUser = account.userId();
                collectionsAt = now;
            }
            return collections.get(id);
        }
    }

    /** A collection's films, in the order they came out. */
    public static List<Item> collection(Account account, String setId) throws IOException {
        return items(Api.getJson(account.server() + "/Users/" + account.userId() + "/Items?parentId=" + setId
                + "&sortBy=ProductionYear,SortName&fields=" + FIELDS
                + "&enableImageTypes=Primary,Backdrop,Logo&imageTypeLimit=1", Library.auth(account)));
    }

    /** Films or shows like {@code id}, by the server's reckoning. */
    public static List<Item> similar(Account account, String id, int limit) throws IOException {
        return items(Api.getJson(account.server() + "/Items/" + id + "/Similar?userId=" + account.userId()
                + "&fields=" + FIELDS + "&enableImageTypes=Primary,Backdrop,Logo&imageTypeLimit=1&limit=" + limit,
                Library.auth(account)));
    }

    /** What in the library {@code personId} is in, of {@code types} ("Movie", "Series"), newest first. */
    public static List<Item> withPerson(Account account, String personId, String types, int limit)
            throws IOException {
        return items(Api.getJson(account.server() + "/Users/" + account.userId()
                + "/Items?recursive=true&personIds=" + personId + "&includeItemTypes=" + types
                + "&sortBy=ProductionYear,SortName&sortOrder=Descending&fields=" + FIELDS
                + "&enableImageTypes=Primary,Backdrop,Logo&imageTypeLimit=1&limit=" + limit, Library.auth(account)));
    }

    /** A person's picture, {@code height} pixels tall; empty when they have none. */
    public static String photo(Account account, Person p, int height) {
        return p.imageTag.isEmpty() ? "" : image(account, p.id, "Primary", p.imageTag, "maxHeight=" + height);
    }

    /** Up to {@code limit} films and shows with a backdrop, in random order (the screensaver's slides). */
    public static List<Item> showcase(Account account, int limit) throws IOException {
        JSONObject page = Api.getJson(account.server() + "/Users/" + account.userId()
                + "/Items?recursive=true&includeItemTypes=Movie,Series&imageTypes=Backdrop&sortBy=Random"
                + "&fields=" + FIELDS + "&enableImageTypes=Backdrop,Logo&imageTypeLimit=1&limit=" + limit,
                Library.auth(account));
        return items(page);
    }

    /** The genres in a library, by name. */
    public static List<String> genres(Account account, String libraryId, Kind kind) throws IOException {
        JSONObject page = Api.getJson(account.server() + "/Genres?userId=" + account.userId()
                + "&includeItemTypes=" + kind.name() + "&sortBy=SortName"
                + (libraryId.isEmpty() ? "" : "&parentId=" + libraryId), Library.auth(account));
        List<String> out = new ArrayList<>();
        for (Item i : items(page)) out.add(i.name);
        return out;
    }

    /** One item in full, with its people. */
    public static Item item(Account account, String id) throws IOException {
        JSONObject o = Api.getJson(account.server() + "/Users/" + account.userId() + "/Items/" + id,
                Library.auth(account));
        return new Item(o);
    }

    public static List<Item> seasons(Account account, String seriesId) throws IOException {
        return items(Api.getJson(account.server() + "/Shows/" + seriesId + "/Seasons?userId=" + account.userId()
                + "&fields=Overview,ChildCount", Library.auth(account)));
    }

    public static List<Item> episodes(Account account, String seriesId, String seasonId) throws IOException {
        return items(Api.getJson(account.server() + "/Shows/" + seriesId + "/Episodes?userId=" + account.userId()
                + "&seasonId=" + seasonId + "&fields=Overview&enableImageTypes=Primary,Thumb&imageTypeLimit=1",
                Library.auth(account)));
    }

    /**
     * The episode to watch next: the one in progress, or the one after the last watched; null when nothing has
     * been watched yet (play from the start) or all of it has.
     */
    public static Item nextUp(Account account, String seriesId) throws IOException {
        List<Item> list = items(Api.getJson(account.server() + "/Shows/NextUp?userId=" + account.userId()
                + "&seriesId=" + seriesId + "&limit=1&fields=Overview", Library.auth(account)));
        return list.isEmpty() ? null : list.get(0);
    }

    public static void setPlayed(Account account, String id, boolean played) throws IOException {
        send(account, played ? "POST" : "DELETE", "/Users/" + account.userId() + "/PlayedItems/" + id);
    }

    public static void setFavourite(Account account, String id, boolean favourite) throws IOException {
        send(account, favourite ? "POST" : "DELETE", "/Users/" + account.userId() + "/FavoriteItems/" + id);
    }

    private static void send(Account account, String method, String path) throws IOException {
        if ("DELETE".equals(method)) Api.delete(account.server() + path, Library.auth(account));
        else Api.post(account.server() + path, Library.auth(account));
    }

    // ---- images --------------------------------------------------------------------------------------

    public static String poster(Account account, Item i, int height) {
        return i.primaryTag.isEmpty() ? "" : image(account, i.id, "Primary", i.primaryTag, "maxHeight=" + height);
    }

    public static String logo(Account account, Item i, int height) {
        return i.logoTag.isEmpty() ? "" : image(account, i.logoId, "Logo", i.logoTag, "maxHeight=" + height);
    }

    public static String backdrop(Account account, Item i, int width) {
        return i.backdropTag.isEmpty() ? "" : image(account, i.backdropId, "Backdrop", i.backdropTag, "maxWidth=" + width);
    }

    /** An episode's own still, or else its show's landscape art. */
    public static String still(Account account, Item i, int width) {
        if (!i.primaryTag.isEmpty()) return image(account, i.id, "Primary", i.primaryTag, "maxWidth=" + width);
        if (!i.thumbTag.isEmpty()) return image(account, i.thumbId, "Thumb", i.thumbTag, "maxWidth=" + width);
        return "";
    }

    private static String image(Account account, String id, String type, String tag, String size) {
        return account.server() + "/Items/" + id + "/Images/" + type + "?tag=" + Uri.encode(tag) + "&" + size
                + "&quality=85";
    }

    // ---- text ----------------------------------------------------------------------------------------

    /**
     * The facts line under a title: "2021 · 15 · 1h 37m · ★ 7.4 · 91%" for a film, "2005–2023 · 12 ·
     * 13 seasons · ★ 8.4" for a show (an open-ended run for one still going). Missing facts are left out.
     */
    public static String facts(Item i, String seasonsLabel) {
        List<String> parts = new ArrayList<>();
        if (i.isSeries()) {
            if (i.year > 0) {
                if (i.continuing) parts.add(i.year + "–");
                else if (i.endYear > i.year) parts.add(i.year + "–" + i.endYear);
                else parts.add(String.valueOf(i.year));
            }
        } else if (i.year > 0) {
            parts.add(String.valueOf(i.year));
        }
        if (!i.officialRating.isEmpty()) parts.add(i.officialRating.replaceFirst("^[A-Z]{2}-", ""));
        if (i.isSeries()) {
            if (seasonsLabel != null) parts.add(seasonsLabel);
        } else if (i.runtimeMs >= 60_000) {
            parts.add(Library.length(i.runtimeMs * 10_000L));
        }
        if (i.communityRating > 0) parts.add(String.format(Locale.ROOT, "★ %.1f", i.communityRating));
        if (i.criticRating > 0) parts.add(i.criticRating + "%");
        return String.join(" · ", parts);
    }

    private static List<Item> items(JSONObject page) {
        List<Item> out = new ArrayList<>();
        JSONArray items = page.optJSONArray("Items");
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject o = items.optJSONObject(i);
            if (o != null) out.add(new Item(o));
        }
        return out;
    }

    private static List<String> strings(JSONArray a) {
        List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
