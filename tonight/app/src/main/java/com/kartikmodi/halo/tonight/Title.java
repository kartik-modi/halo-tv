package com.kartikmodi.halo.tonight;

import java.util.ArrayList;
import java.util.List;

/** One film or series from TMDB, filled in two steps: the list fields from discover, the rest on focus. */
final class Title {
    final int id;
    final boolean series;
    final String name;
    final String year;
    final String overview;
    final String posterPath;
    final String backdropPath;
    final List<Integer> genreIds;
    final double popularity;

    // filled by Tmdb.details()
    volatile boolean detailed;
    /** The code of the service that streams it (see Services); null when none of the configured ones does. */
    volatile String service;
    volatile String meta = "";
    volatile String genresText = "";
    volatile String textlessPoster;
    /** The title's own logo artwork (transparent), English or language-free. */
    volatile String logoPath;
    volatile String leadName;
    volatile String leadCharacter;
    volatile String leadProfile;
    volatile int leadPersonId;
    final List<String> genreNames = new ArrayList<>();

    Title(int id, boolean series, String name, String year, String overview, String posterPath, String backdropPath,
          List<Integer> genreIds, double popularity) {
        this.id = id;
        this.series = series;
        this.name = name;
        this.year = year;
        this.overview = overview;
        this.posterPath = posterPath;
        this.backdropPath = backdropPath;
        this.genreIds = genreIds;
        this.popularity = popularity;
    }

    String key() {
        return (series ? "tv:" : "movie:") + id;
    }
}
