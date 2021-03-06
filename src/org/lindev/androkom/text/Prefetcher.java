package org.lindev.androkom.text;

import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import nu.dll.lyskom.Membership;
import nu.dll.lyskom.Membership.Range;
import nu.dll.lyskom.Text;
import nu.dll.lyskom.TextMapping;
import nu.dll.lyskom.UConference;

import org.lindev.androkom.KomServer;
import org.lindev.androkom.KomServer.TextInfo;

import android.os.AsyncTask;
import android.util.Log;

class Prefetcher {
    private static final String TAG = "Androkom Prefetcher";

    private static final Pattern TEXT_LINK_FINDER = Pattern.compile("\\d{5,}");
    //private static final int MAX_PREFETCH = (int) (Runtime.getRuntime().maxMemory()/(1024*1024))-10;
    private static final int MAX_PREFETCH = 5;
    private static final int ASK_AMOUNT = 2 * MAX_PREFETCH;
    private static boolean ENABLE_CACHE_RELEVANT = false;

    private KomServer mKom = null;
    private final TextCache mTextCache;

    private final BlockingQueue<TextConf> mUnreadQueue;
    private final Set<Integer> mRelevantCached;

    private PrefetchNextUnread mPrefetchRunner = null;

    Prefetcher(final KomServer kom, final TextCache textCache) {
        Log.d(TAG, "MAX_PREFETCH="+MAX_PREFETCH);
        this.mKom = kom;
        this.mTextCache = textCache;
        this.mUnreadQueue = new ArrayBlockingQueue<TextConf>(MAX_PREFETCH);
        this.mRelevantCached = new HashSet<Integer>();
    }

    private class TextConf {
        private final int textNo;
        private final int confNo;

        TextConf (final int textNo, final int confNo) {
            this.textNo = textNo;
            this.confNo = confNo;
        }
    }

    private class PrefetchNextUnread extends Thread {
        private final Queue<Integer> mUnreadConfs;
        private final Set<Integer> mEnqueued;

        private Iterator<Integer> mMaybeUnreadIter = null;
        private int mCurrConf = -1;
        private int mCurrConfLastRead = -1;
        private boolean mIsInterrupted = false;

        PrefetchNextUnread(final int confNo) {
            Log.i(TAG, "PrefetchNextUnread starting in conference " + confNo);
            this.mUnreadConfs = new LinkedList<Integer>();
            this.mEnqueued = new HashSet<Integer>();
            if(!mKom.isConnected()) {
                Log.d(TAG, "PrefetchNextUnread not connected");
                return;
            }
            initialize(confNo);
        }

        private void initialize(final int confNo) {
            if(!mKom.isConnected()) {
                Log.d(TAG, "PrefetchNextUnread.initialize not connected");
                return;
            }
            List<Integer> unreadConfList = null;
            try {
                unreadConfList = mKom.getUnreadConfsListCached();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            if(unreadConfList==null) {
                Log.d(TAG, "initialize unreadConfList==null");
                return;
            }
            int startIdx;
            if(confNo > 0) {
                startIdx = unreadConfList.indexOf(confNo);
            } else {
                startIdx = 0;
            }
            if (startIdx < 0) {
                startIdx = 0;
            }
            for (int i = startIdx; i < unreadConfList.size(); ++i) {
                Log.i(TAG, "PrefetchNextUnread enqueuing unread conference " + unreadConfList.get(i));
                mUnreadConfs.add(unreadConfList.get(i));
            }
            for (int i = 0; i < startIdx; ++i) {
                Log.i(TAG, "PrefetchNextUnread enqueuing unread conference " + unreadConfList.get(i));
                mUnreadConfs.add(unreadConfList.get(i));
            }
            this.mMaybeUnreadIter = Collections.<Integer>emptyList().iterator();
        }

        private void enqueueAndPrefetch(final int textNo, final int confNo) {
            Log.d(TAG, "PrefetchNextUnread.enqueueAndPrefetch " + textNo);
            final boolean notEnqueued = mEnqueued.add(textNo);
            if (!mKom.isConnected()) {
                Log.d(TAG,
                        "PrefetchNextUnread.enqueueAndPrefetch not connected");
                return;
            }
            if (notEnqueued && !mKom.isLocalRead(textNo)) {
                try {
                    mUnreadQueue.put(new TextConf(textNo, confNo));
                } catch (final InterruptedException e) {
                    // Someone called interrupt() on this thread. We shouldn't
                    // do anything more, just exit.
                    Log.i(TAG, "PrefetchNextUnread was interrupted");
                    return;
                }
                Thread backgroundThread = new Thread(new Runnable() {
                    public void run() {
                        try {
                            Thread.sleep(200); // Yield to GUI, ie delay filling up queue at back until item at front has been processed
                        } catch (InterruptedException e) {
                            Log.i(TAG, "PrefetchNextUnread thread.sleep interrupted for prefetching text "
                                    + textNo + " in conference " + confNo);
                        }
                        Log.i(TAG, "PrefetchNextUnread prefetching text "
                                + textNo + " in conference " + confNo);
                        mTextCache.getCText(textNo);
                    }
                });
                backgroundThread.start();
            }
        }

        private int lastTextReadFrom(final Membership membership, final int from) {
            final List<Range> readRanges = membership.getReadRanges();
            if(!mKom.isConnected()) {
                Log.d(TAG, "PrefetchNextUnread.lastTextReadFrom not connected");
                return -1;
            }
            if (from < 0) {
                return membership.getLastTextRead();
            }
            synchronized (readRanges) {
                for (final Range range : readRanges) {
                    if (from < range.first) {
                        return from;
                    }
                    else if (from <= range.last) {
                        return range.last;
                    }
                }
                return readRanges.get(readRanges.size() - 1).last;
            }
        }

        public Iterator<Integer> askServerForMore() {
            if(!mKom.isConnected()) {
                Log.d(TAG, "PrefetchNextUnread.askServerForMore not connected");
                return null;
            }
            mCurrConf = mUnreadConfs.element();
            Log.i(TAG, "PrefetchNextUnread askServerForMore mCurrConf: " + mCurrConf + " mCurrConfLocalNo: " + mCurrConfLastRead);
            Membership membership = null;
            TextMapping tm = null;
            final List<Integer> maybeUnread = new ArrayList<Integer>();
            try {
                membership = mKom.queryReadTexts(mKom.getUserId(), mCurrConf, true);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            mCurrConfLastRead = lastTextReadFrom(membership, mCurrConfLastRead);
            Log.i(TAG, "PrefetchNextUnread mCurrConfLastRead: " + mCurrConfLastRead);
            UConference conf = null;
            try {
                conf = mKom.getUConfStat(mCurrConf);
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            if ((conf != null) && mCurrConfLastRead < conf.getHighestLocalNo()) {
                try {
                    tm = mKom.localToGlobal(mCurrConf, mCurrConfLastRead + 1, ASK_AMOUNT);
                } catch (InterruptedException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
                if(tm != null) {
                    Log.i(TAG, "PrefetchNextUnread asked for " + ASK_AMOUNT + " texts in conf " + mCurrConf + ", got " + tm.size());
                } else {
                    Log.i(TAG, "PrefetchNextUnread asked for " + ASK_AMOUNT + " texts in conf " + mCurrConf + ", got null");
                }
            }
            else {
                Log.i(TAG, "PrefetchNextUnread too high local number in " + mCurrConf);
                tm = null;
            }
            while (tm != null && tm.hasMoreElements()) {
                final int globalNo = (Integer) tm.nextElement();
                final int localNo = tm.local();
                if ((membership != null) && (!membership.isRead(localNo))) {
                    Log.i(TAG, "PrefetchNextUnread adding localNo " + localNo + " (globalNo " + globalNo + ")");
                    maybeUnread.add(globalNo);
                }
                mCurrConfLastRead = Math.max(mCurrConfLastRead, localNo);
            }
            if (tm == null || !tm.laterTextsExists()) {
                Log.i(TAG, "PrefetchNextUnread no later texts exists in conf " + mCurrConf);
                mUnreadConfs.remove();
                mCurrConfLastRead = -1;
            }
            return maybeUnread.iterator();
        }

        @Override
        public void run() {
            while (!mIsInterrupted) {
                Log.d(TAG, " run in new loop");
                if (!mKom.isConnected()) {
                    Log.d(TAG, " run not connected");
                    mIsInterrupted = true;
                }
                if (mMaybeUnreadIter == null) {
                    Log.d(TAG, " run not connected (is null)");
                    Log.i(TAG,
                            "PrefetchNextUnread is exiting because it was interrupted 1");
                    mIsInterrupted = true;
                } else if (mMaybeUnreadIter.hasNext()) {
                    final int textNo = mMaybeUnreadIter.next();
                    enqueueAndPrefetch(textNo, mCurrConf);
                } else if (!mUnreadConfs.isEmpty()) {
                    // Ask the server for more (possibly) unread texts
                    try {
                        Log.i(TAG, "PrefetchNextUnread asks for more");
                        mMaybeUnreadIter = askServerForMore();
                    } catch (NullPointerException e) {
                        Log.d(TAG, " run not connected (NullPointer)");
                        mMaybeUnreadIter = null;
                    }
                    if ((mMaybeUnreadIter == null)
                            || (!mMaybeUnreadIter.hasNext())) {
                        Log.i(TAG,
                                "PrefetchNextUnread might be more to read but connection is probably gone");
                        mIsInterrupted = true; /*
                                                * might be more to read but
                                                * connection is probably gone
                                                */
                    }
                } else {
                    // No more unread in conference, and no more unread
                    // conferences
                    Log.d(TAG, " run: no more unread");
                    mIsInterrupted = false;
                    break;
                }
            }

            // If the thread wasn't interrupted, we should put an end marker on
            // the queue.
            if (!mIsInterrupted) {
                try {
                    // Enqueue the marker that there are no more unread texts
                    Log.i(TAG,
                            "PrefetchNextUnread found no more unread. Exiting.");
                    mUnreadQueue.put(new TextConf(-1, -1));
                } catch (final InterruptedException e) {
                    Log.i(TAG,
                            "PrefetchNextUnread is exiting because it was interrupted by exception");
                }
            } else {
                Log.i(TAG,
                        "PrefetchNextUnread is exiting because it was interrupted 2");
            }
            Log.i(TAG,
                    "PrefetchNextUnread is exiting.");
        }
    }

    int getNextUnreadTextNo() {
        Log.d(TAG, " *** getNextUnreadTextNo ***");

        if(!mKom.isConnected()) {
            Log.d(TAG, " getNextUnreadTextNo not connected");
            return 0;
        }
        // If mPrefetchRunner is null, we have already reached the end of the queue
        if (mPrefetchRunner == null) {
            Log.d(TAG, " getNextUnreadTextNo end of queue");
            return 0;
        }

        // Get the next unread text from the queue
        TextConf tc = null;
        int i=0;
        Log.d(TAG, " getNextUnreadTextNo take1");
        while((tc == null) && (i < 20)) {
            tc = mUnreadQueue.peek();
            if(tc == null) {
                Log.d(TAG, "getNextUnreadTextNo waiting for textno. loop#"+i);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.d(TAG, "getNextUnreadTextNo wait interrupted");
                    //e.printStackTrace();
                }
            }
            i++;
        }
        Log.d(TAG, " getNextUnreadTextNo take2");
        if(tc==null) {
            Log.d(TAG, " getNextUnreadTextNo next text would be kind of null?");
            return 0;
        } else {
            Log.d(TAG, " getNextUnreadTextNo next text would be:" + tc.textNo);
        }
        
        return tc.textNo;
    }
    
    TextInfo getNextUnreadText(final boolean cacheRelevant, final boolean peekQueue) {
        Log.d(TAG, " +++ getNextUnreadText +++");
        if(!mKom.isConnected()) {
            Log.d(TAG, " getNextUnreadText not connected");
            return null;
        }
        // If mPrefetchRunner is null, we have already reached the end of the queue
        if (mPrefetchRunner == null) {
            Log.d(TAG, " getNextUnreadText end of queue. No mPrefetchRunner.");
            return TextInfo.createText(mKom.getBaseContext(), TextInfo.ALL_READ);
        }

        // Get the next unread text from the queue
        final TextConf tc;
        try {
            if (peekQueue) {
                tc = mUnreadQueue.peek();
            } else {
                Log.d(TAG, " getNextUnreadText take1");
                tc = mUnreadQueue.poll(10, TimeUnit.SECONDS);
                Log.d(TAG, " getNextUnreadText take2");
            }
        } catch (final InterruptedException e) {
            Log.d(TAG, " getNextUnreadText exception: "+e);
            return TextInfo.createText(mKom.getBaseContext(), TextInfo.ERROR_FETCHING_TEXT);
        }
        Log.d(TAG, " getNextUnreadText mUnreadQueue.size="+mUnreadQueue.size());
        if(tc==null) {
            Log.d(TAG, " getNextUnreadText next text would be kind of null. IE timeout.");
            Log.d(TAG, " getNextUnreadText mPrefetchRunner==null:"+(mPrefetchRunner==null));
            return null;
        } else {
            Log.d(TAG, " getNextUnreadText next text would be:" + tc.textNo);
        }
        // This is how the prefetcher marks that there are no more unread texts. mPrefetchRunner should be finished,
        // so we can delete the reference to it.
        if (tc.textNo < 0) {
            Log.d(TAG, " getNextUnreadText mark no more unread");

            mPrefetchRunner = null;
            return TextInfo.createText(mKom.getBaseContext(), TextInfo.ALL_READ);
        }

        // If the text is already locally marked as read, get the next one instead
        if (mKom.isLocalRead(tc.textNo)) {
            //Log.d(TAG, " getNextUnreadText already read, get another");
            //Seems to run out of stack sometimes
            //return getNextUnreadText(cacheRelevant, peekQueue);
            return null;
        }

        // Switch conference name
        //Log.d(TAG, " getNextUnreadText change conf name");
        try {
            mKom.setConferenceName(mKom.getConferenceName(tc.confNo));
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            Log.d(TAG, " getNextUnreadText InterruptedException");
        }

        // Retrieve the text
        //Log.d(TAG, " getNextUnreadText get text from cache:"+tc.textNo);
        final TextInfo text = mTextCache.getDText(tc.textNo);

        if(text==null) {
            Log.d(TAG, "Got null text!?!?");
        }
        // Cache relevant info both for this text and for the next in the queue
        // (if available)
        if (cacheRelevant) {
            Thread backgroundThread = new Thread(new Runnable() {
                public void run() {
                    // Log.d(TAG, " getNextUnreadText cache relevant");
                    doCacheRelevant(tc.textNo);
                    final TextConf tcNext = mUnreadQueue.peek();
                    if (tcNext != null) {
                        doCacheRelevant(tcNext.textNo);
                    }
                    // Log.d(TAG, " getNextUnreadText cache done");
                }
            });
            backgroundThread.setPriority(Thread.MIN_PRIORITY);
            backgroundThread.start();
        }

        if(text != null) {
            Log.d(TAG, " getNextUnreadText returning textno "+text.getTextNo());
        } else {
            Log.d(TAG, " getNextUnreadText returning null ");
        }
        return text;
    }

    void start(final int confNo) {
        if (mPrefetchRunner != null) {
            Log.i(TAG, "TextFetcher startPrefetcher(), already started");
            mPrefetchRunner.interrupt();
        }
        Log.i(TAG, "TextFetcher startPrefetcher(), starting");
        mUnreadQueue.clear();
        mPrefetchRunner = new PrefetchNextUnread(confNo);
        mPrefetchRunner.start();
    }

    void restart(final int confNo) {
        if (mPrefetchRunner != null) {
            Log.i(TAG, "TextFetcher restartPrefetcher(), interrupting old PrefetchRunner");
            interruptPrefetcher();
            mTextCache.clearCacheStat();
        }
        mUnreadQueue.clear();
        mPrefetchRunner = new PrefetchNextUnread(confNo);
        mPrefetchRunner.start();
    }

    void interruptPrefetcher() {
        if (mPrefetchRunner != null) {
            Log.i(TAG, "TextFetcher interruptPrefetch(), interrupting old PrefetchRunner");
            mPrefetchRunner.mIsInterrupted = true;
            mPrefetchRunner.interrupt();
        }
    }

    private class CacheRelevantTask extends AsyncTask<Integer, Void, Void> {
        @Override
        protected Void doInBackground(final Integer... args) {
            Log.d(TAG, " CacheRelevantTask.doInBackground started");
            if(!mKom.isConnected()) {
                Log.d(TAG, " CacheRelevantTask.doInBackground not connected");
                return null;
            }
            final int textNo = args[0];
            //final TextInfo textInfo = mTextCache.getDText(textNo);
            final Text text;
            try {
                text = mKom.getTextbyNo(textNo);
            } catch (final Exception e) {
                Log.d(TAG, "CacheRelevantTask getText failed:"+e);
                //e.printStackTrace();
                return null;
            }

            final List<Integer> texts = new ArrayList<Integer>();
            for (int comment : text.getComments()) {
                Log.i(TAG, "CacheRelevantTask " + comment + " is a comment to " + textNo);
                texts.add(comment);
            }
            for (int footnote : text.getFootnotes()) {
                Log.i(TAG, "CacheRelevantTask " + footnote + " is a footnote to " + textNo);
                texts.add(footnote);
            }
            for (int commented : text.getCommented()) {
                Log.i(TAG, "CacheRelevantTask " + commented + " is a parent to " + textNo);
                texts.add(commented);
            }

            String textbody;
            try {
                textbody = text.getBodyString();
                // final Matcher m =
                // TEXT_LINK_FINDER.matcher(textInfo.getBody());
                final Matcher m = TEXT_LINK_FINDER.matcher(textbody);
                while (m.find()) {
                    final String str = textbody.substring(m.start(), m.end());
                    try {
                        final int linkNo = Integer.valueOf(str);
                        Log.i(TAG, "CacheRelevantTask, text number " + linkNo
                                + " found in body of " + textNo);
                        texts.add(linkNo);
                    } catch (final NumberFormatException e) {
                        Log.i(TAG, "CacheRelevantTask, unable to parse " + str
                                + " as text number in body of " + textNo);
                    }
                }
            } catch (UnsupportedEncodingException e1) {
                // TODO Auto-generated catch block
                e1.printStackTrace();
            }

            for (final int t : texts) {
                mTextCache.getCText(t);
            }

            Log.d(TAG, " CacheRelevantTask.doInBackground done");
            return null;
        }
    }

    /**
     * Cache all comments and footnotes to a text
     */
    void doCacheRelevant(final int textNo) {
        if(!mKom.isConnected()) {
            Log.d(TAG, " doCacheRelevant not connected");
            return;
        }
        if (!ENABLE_CACHE_RELEVANT || textNo <= 0) {
            return;
        }
        final boolean needCaching;
        synchronized (mRelevantCached) {
            needCaching = mRelevantCached.add(textNo);
        }
        if (needCaching) {
            //if(android.os.Build.VERSION.SDK_INT > 12) {
                //new CacheRelevantTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, textNo);
            new CacheRelevantTask().execute(textNo);
        }
    }

/*    public void removeTextFromCache(int textNo) {
        synchronized (mTextCache) {
            Log.d(TAG, "removeTextFromCache "+textNo);
            mTextCache.removeTextFromCache(textNo);
        }
    }
*/
}
