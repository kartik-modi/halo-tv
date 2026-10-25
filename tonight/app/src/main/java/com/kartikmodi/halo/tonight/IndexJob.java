package com.kartikmodi.halo.tonight;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

/**
 * Keeps the search index fresh by itself: a full rebuild once a week and a quick refresh once a day, whenever the
 * TV is online, kept across reboots. A first build runs as soon as the app opens without an index.
 */
public final class IndexJob extends JobService {
    private static final String TAG = "TonightIndex";
    private static final int WEEKLY = 7101, DAILY = 7102, NOW = 7103;
    private static final long DAY = 24 * 3600 * 1000L;

    /** Called when TONIGHT. opens: makes sure both jobs are scheduled, and builds now if there is no index yet. */
    static void schedule(Context c) {
        JobScheduler js = c.getSystemService(JobScheduler.class);
        if (js == null) return;
        if (js.getPendingJob(WEEKLY) == null) js.schedule(job(c, WEEKLY).setPeriodic(7 * DAY, DAY).build());
        if (js.getPendingJob(DAILY) == null) js.schedule(job(c, DAILY).setPeriodic(DAY, 6 * 3600 * 1000L).build());
        SearchIndex index = SearchIndex.get(c);
        boolean stale = System.currentTimeMillis() - index.builtAt() > 8 * DAY;   // a weekly run was missed
        if ((!index.exists() || stale) && !index.building() && js.getPendingJob(NOW) == null) {
            js.schedule(job(c, NOW).setOverrideDeadline(1000).build());
        }
    }

    private static JobInfo.Builder job(Context c, int id) {
        return new JobInfo.Builder(id, new ComponentName(c, IndexJob.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                // stopped part-way (the TV frees memory for video): try again in 30 s, 60 s, ... from the saved chunks
                .setBackoffCriteria(30000, JobInfo.BACKOFF_POLICY_LINEAR)
                .setPersisted(true);
    }

    @Override
    public boolean onStartJob(JobParameters p) {
        boolean full = p.getJobId() != DAILY;
        Log.i(TAG, (full ? "full" : "quick") + " index job started");
        new Thread(() -> {
            SearchIndex.get(this).build(full);
            jobFinished(p, false);
        }, "TonightIndex").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) {
        return true;   // stopped part-way (the TV went offline): try again later; the old index stays in use
    }
}
