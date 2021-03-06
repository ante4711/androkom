package org.lindev.androkom.text;

import java.io.IOException;

import nu.dll.lyskom.Text;

import org.lindev.androkom.ConferencePrefs;
import org.lindev.androkom.KomServer;
import org.lindev.androkom.R;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;

public class SendTextTask extends AsyncTask<Void, Void, String> {
    private static final String TAG = "Androkom SendTextTask";
    private final ProgressDialog mDialog;
    private final Context mContext;
    private final KomServer mKom;
    private final Text mText;
    private final Runnable mRunnable;

    public SendTextTask(final Context context, final KomServer kom, final Text text, final Runnable runnable) {
        this.mDialog = new ProgressDialog(context);
        this.mContext = context;
        this.mKom = kom;
        this.mText = text;
        this.mRunnable = runnable;
    }

    @Override
    protected void onPreExecute() {
        try {
            mDialog.setCancelable(false);
            mDialog.setIndeterminate(true);
            mDialog.setMessage("Sending text ...");
            mDialog.show();
        } catch (java.lang.IllegalArgumentException e) {
            Log.d(TAG, "onPreExecute1 caught IAexception: " + e);
        } catch (Exception e) {
            Log.d(TAG, "onPreExecute1 caught exception: " + e);
        }
    }

    @Override
    protected String doInBackground(final Void... args) {
        try {
            final int id = mKom.createText(mText, ConferencePrefs.getAutoMarkOwnTextRead(mContext));
            if(id!=0) {
                mKom.mPendingSentTexts.add(id);
            } else {
                Log.d(TAG, "Failed to create text");
            }
        }
        catch (final IOException e) {
            return "IOException";
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        return null;
    }

    @Override
    protected void onPostExecute(final String error) {
        try {
            mDialog.dismiss();
        } catch (java.lang.IllegalArgumentException e) {
            Log.d(TAG, "onPostExecute1 caught IAexception: " + e);
            Log.d(TAG, "onPostExecute1 got error:" + error);
        } catch (Exception e) {
            Log.d(TAG, "onPostExecute1 caught exception: " + e);
            Log.d(TAG, "onPostExecute1 got error:" + error);
        }

        try {
            if (error != null) {
                final AlertDialog.Builder builder = new AlertDialog.Builder(
                        mContext);
                builder.setTitle(error);
                builder.setPositiveButton(
                        mContext.getString(R.string.alert_dialog_ok), null);
                builder.create().show();
            } else if (mRunnable != null) {
                mRunnable.run();
            }
        } catch (java.lang.IllegalArgumentException e) {
            Log.d(TAG, "onPostExecute2 caught IAexception: " + e);
            Log.d(TAG, "onPostExecute2 got error:" + error);
        } catch (Exception e) {
            Log.d(TAG, "onPostExecute2 caught exception: " + e);
            Log.d(TAG, "onPostExecute2 got error:" + error);
        }
    }
}
