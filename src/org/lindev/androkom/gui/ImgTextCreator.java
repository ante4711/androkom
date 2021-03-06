package org.lindev.androkom.gui;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import nu.dll.lyskom.ConfInfo;
import nu.dll.lyskom.Conference;
import nu.dll.lyskom.RpcFailure;
import nu.dll.lyskom.Text;

import org.lindev.androkom.App;
import org.lindev.androkom.ConferencePrefs;
import org.lindev.androkom.Consts;
import org.lindev.androkom.KomServer;
import org.lindev.androkom.LocalBinder;
import org.lindev.androkom.LookupNameTask;
import org.lindev.androkom.LookupNameTask.LookupType;
import org.lindev.androkom.LookupNameTask.RunOnSuccess;
import org.lindev.androkom.R;
import org.lindev.androkom.text.Recipient;
import org.lindev.androkom.text.Recipient.RecipientType;
import android.app.AlertDialog;
import android.app.TabActivity;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TabHost;
import android.widget.TabHost.TabSpec;

public class ImgTextCreator extends TabActivity implements ServiceConnection {
    public static final String TAG = "Androkom ImgTextCreator";

    private static final String TEXT_TAB_TAG = "text-tab-tag";
    private static final String RECIPIENTS_TAB_TAG = "recipients-tab-tag";

    private static final String INTENT_INITIAL_RECIPIENTS_ADDED = "initial-recipients-added";
    public static final String INTENT_REPLY_TO = "in-reply-to";
    public static final String INTENT_SUBJECT = "subject-line";
    public static final String INTENT_RECIPIENT = "recipient";
    public static final String INTENT_IS_MAIL = "is-mail";

    private KomServer mKom = null;
    private List<Recipient> mRecipients;
    private ArrayAdapter<Recipient> mAdapter;
    private int mReplyTo;
    private EditText mSubject;
    
    public class CopyRecipientsTask extends AsyncTask<Integer, Void, List<Recipient>> {
        @Override
        protected List<Recipient> doInBackground(final Integer... args) {
            final List<Recipient> recipients = new ArrayList<Recipient>();

            try {
                final Text text = mKom.getTextbyNo(args[0]);
                if (text == null) {
                    return null;
                }
                for (int recip : text.getRecipients()) {
                    final Conference confStat = mKom.getConfStat(recip);
                    if (confStat != null) {
                        if (confStat.getConfInfo().confType.original()) {
                            recip = confStat.getSuperConf();
                        }
                        final String name = mKom.getConferenceName(recip);
                        recipients.add(new Recipient(mKom
                                .getApplicationContext(), recip, name,
                                RecipientType.RECP_TO));
                    }
                }
            }
            catch (final RpcFailure e) {
                return null;
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            return recipients;
        }

        @Override
        public void onPostExecute(final List<Recipient> recipients) {
            if (recipients != null) {
                for (final Recipient recipient : recipients) {
                    add(recipient);
                }
            }
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        setContentView(R.layout.create_new_imgtext_layout);

        mHandler = new Handler() {
            @Override
            public void handleMessage(Message msg) {
                consumeMessage(msg);
            }
        };

        initializeCommon();
        initializeRecipients();
        initializeTabs();
        initializeButtons();
        Thread backgroundThread = new Thread(new Runnable() {
            public void run() {
                initializeImg();
            }
        });
        backgroundThread.start();
        
        getApp().doBindService(this);
    }
    
    @Override
    protected void onDestroy() {
        getApp().doUnbindService(this);
        super.onDestroy();
    }

    private void initializeCommon() {
        mSubject = (EditText) findViewById(R.id.subject);
        mReplyTo = getIntent().getIntExtra(INTENT_REPLY_TO, -1);
        if (mReplyTo > 0) {
            setTitle(getString(R.string.creator_comment_to) + mReplyTo);
        }
        else {
            setTitle(getString(R.string.creator_new_text));
        }
    }

    private void initializeImg() {
        Log.d(TAG, "initializeImg started");
        String uri_string = getIntent().getStringExtra("bild_uri");
        Bitmap bmImg = null;
        if ((uri_string != null) && (uri_string.length() > 0)) {
            Uri uri = Uri.parse(uri_string);
            if (uri != null) {
                Log.d(TAG, "got filename=" + uri.getPath());
                imgFilename = uri.getLastPathSegment();

                // Query gallery for camera picture via
                // Android ContentResolver interface
                ContentResolver cr = getContentResolver();

                bmImg = getBitmap(cr, uri);
            } else {
                Log.d(TAG, "initializeImg got null uri. bailing out");
                return;
            }
        } else {
            Log.d(TAG, "Got embedded image");
            Intent intent = getIntent();
            if (intent != null) {
                bmImg = (Bitmap) intent.getParcelableExtra("BitmapImage");
            } else {
                Log.d(TAG, "initializeImg got null intent. bailing out");
                return;
            }
        }
        if (bmImg != null) {
            Log.d(TAG, " initializeImg Height:" + bmImg.getHeight() + " with:"
                    + bmImg.getWidth());
        } else {
            Log.d(TAG, " initializeImg got null bmImg. Bailing out");
            return;
        }
        final ImageView imgView = (ImageView) findViewById(R.id.imageView1);
        final Bitmap set_bmImg = bmImg;
        runOnUiThread(new Runnable() {
            public void run() {
                Log.d(TAG, "initializeImg setImageBitmap");
                imgView.setImageBitmap(set_bmImg);
            }
        });
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        bmImg.compress(Bitmap.CompressFormat.JPEG, 85, buffer);
        Log.d(TAG, " initializeImg compressed Height:" + bmImg.getHeight()
                + " with:" + bmImg.getWidth());
        try {
            buffer.flush();
        } catch (IOException e) {
            Log.d(TAG, "initializeImg " + e);
            // e.printStackTrace();
        }
        imgdata = buffer.toByteArray();
        Log.d(TAG, "compressed imagesize:" + imgdata.length);
        return;
    }

    private Bitmap getBitmap(ContentResolver cr, Uri uri) {
        try {
            InputStream is = cr.openInputStream(uri);
            final int IMAGE_MAX_SIZE = ConferencePrefs.getMaxImageSizePix(this);

            // Decode image size
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeStream(is, null, o);
            is.close();

            Log.d(TAG, " getBitmap Height:"+o.outHeight+" with:"+o.outWidth);

            int scale = 1;
            while ((o.outWidth * o.outHeight) * (1 / Math.pow(scale, 2)) > IMAGE_MAX_SIZE) {
                scale++;
            }
            Log.d(TAG, "scale = " + scale + ", orig-width: " + o.outWidth
                    + ", orig-height: " + o.outHeight);

            Bitmap b = null;
            is = cr.openInputStream(uri);
            if (scale > 1) {
                scale--;
                // scale to max possible inSampleSize that still yields an image
                // larger than target
                o = new BitmapFactory.Options();
                o.inSampleSize = scale;
                b = BitmapFactory.decodeStream(is, null, o);

                // resize to desired dimensions
                int height = b.getHeight();
                int width = b.getWidth();
                Log.d(TAG, "1th scale operation dimensions - width: " + width
                        + ", height: " + height);

                double y = Math.sqrt(IMAGE_MAX_SIZE
                        / (((double) width) / height));
                double x = (y / height) * width;

                Bitmap scaledBitmap = Bitmap.createScaledBitmap(b, (int) x,
                        (int) y, true);
                b.recycle();
                b = scaledBitmap;

                System.gc();
            } else {
                b = BitmapFactory.decodeStream(is);
            }
            is.close();

            Log.d(TAG, "bitmap size - width: " + b.getWidth() + ", height: "
                    + b.getHeight());
            return b;
        } catch (IOException e) {
            Log.e(TAG, e.getMessage(), e);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private void initializeRecipients() {
        mAdapter = new ArrayAdapter<Recipient>(this, R.layout.message_log);
        mRecipients = (List<Recipient>) getLastNonConfigurationInstance();
        if (mRecipients == null) {
            mRecipients = new ArrayList<Recipient>();
        }
        for (final Recipient recipient : mRecipients) {
            mAdapter.add(recipient);
        }
        mAdapter.notifyDataSetChanged();
    }

    private void initializeTabs() {
        final TabHost tabHost = getTabHost();
        final TabSpec textTab = tabHost.newTabSpec(TEXT_TAB_TAG);
        textTab.setIndicator(getString(R.string.creator_text_title));
        textTab.setContent(R.id.create_img_text);
        tabHost.addTab(textTab);

        final TabSpec recipientsTab = tabHost.newTabSpec(RECIPIENTS_TAB_TAG);
        recipientsTab.setIndicator(getString(R.string.creator_recipents_title));
        recipientsTab.setContent(R.id.img_recipients_view);
        tabHost.addTab(recipientsTab);

        final ListView recipientsView = (ListView) findViewById(R.id.recipients);
        recipientsView.setAdapter(mAdapter);
        recipientsView.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(final AdapterView<?> av, final View view, final int position, final long id) {
                final Recipient recipient = (Recipient) av.getItemAtPosition(position);
                showRemoveRecipientDialog(recipient);
            }
        });
    }

    private void initializeButtons() {
        final Button toButton = (Button) findViewById(R.id.add_to);
        final Button ccButton = (Button) findViewById(R.id.add_cc);
        final Button bccButton = (Button) findViewById(R.id.add_bcc);
        final Button sendButton = (Button) findViewById(R.id.send);
        final Button cancelButton = (Button) findViewById(R.id.cancel);

        toButton.setEnabled(false);
        ccButton.setEnabled(false);
        bccButton.setEnabled(false);
        sendButton.setEnabled(false);
        cancelButton.setEnabled(true);

        final View.OnClickListener buttonClickListener = new View.OnClickListener() {
            public void onClick(final View view) {
                if (view == toButton) {
                    showAddRecipientDialog(RecipientType.RECP_TO, LookupType.LOOKUP_BOTH);
                }
                else if (view == ccButton) {
                    showAddRecipientDialog(RecipientType.RECP_CC, LookupType.LOOKUP_BOTH);
                }
                else if (view == bccButton) {
                    showAddRecipientDialog(RecipientType.RECP_BCC, LookupType.LOOKUP_BOTH);
                }
                else if (view == sendButton) {
                    sendMessage();
                }
                else if (view == cancelButton) {
                    finish();
                }
            }
        };

        toButton.setOnClickListener(buttonClickListener);
        ccButton.setOnClickListener(buttonClickListener);
        bccButton.setOnClickListener(buttonClickListener);
        sendButton.setOnClickListener(buttonClickListener);
        cancelButton.setOnClickListener(buttonClickListener);
    }

    private void enableButtons() {
        ((Button) findViewById(R.id.add_to)).setEnabled(true);
        ((Button) findViewById(R.id.add_cc)).setEnabled(true);
        ((Button) findViewById(R.id.add_bcc)).setEnabled(true);
        ((Button) findViewById(R.id.send)).setEnabled(true);
    }

    private void showRemoveRecipientDialog(final Recipient recipient) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.creator_remove_recipient)+"\n"
                + recipient.recipientStr + "?");
        builder.setNegativeButton(getString(R.string.no), null);
        builder.setPositiveButton(getString(R.string.yes), new OnClickListener() {
            public void onClick(final DialogInterface dialog, final int which) {
                remove(recipient);
            }
        });
        builder.create().show();
    }

    private void showAddRecipientDialog(final RecipientType type, final LookupType lookupType) {
        final EditText input = new EditText(this);
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.creator_add_recipient));
        builder.setView(input);
        builder.setNegativeButton(getString(R.string.alert_dialog_cancel), null);
        builder.setPositiveButton(getString(R.string.alert_dialog_ok), new OnClickListener() {
            public void onClick(final DialogInterface dialog, final int which) {
                final String recip = input.getText().toString();
                new LookupNameTask(ImgTextCreator.this, mKom, recip, lookupType, new RunOnSuccess() {
                    public void run(final ConfInfo conf) {
                        add(new Recipient(mKom.getApplicationContext(), conf.getNo(), conf.getNameString(), type));
                    }
                }).execute();
            }
        });
        builder.create().show();
    }

    protected void consumeMessage(final Message msg) {
        switch (msg.what) {
        case Consts.MESSAGE_SENDTEXT:
            String subject = (String) msg.obj;
            CreateText ct = new CreateText(mKom, subject, imgFilename, imgdata, mReplyTo, mRecipients);
            Text text = ct.process(ConferencePrefs.getIncludeLocation(this));
            try {
                final int id = mKom.createText(text, ConferencePrefs.getAutoMarkOwnTextRead(this));
                if(id!=0) {
                    mKom.mPendingSentTexts.add(id);
                } else {
                    Log.d(TAG, "Failed to create text");
                }
            }
            catch (final IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
            setProgressBarIndeterminateVisibility(false);
            Message rmsg = new Message();
            rmsg.what = Consts.MESSAGE_FINISH;
            rmsg.obj = subject;
            mHandler.sendMessage(rmsg);
            break;
        case Consts.MESSAGE_FINISH:
            finish();
            break;
        default:
            Log.d(TAG, "consumeMessage ERROR unknown msg.what=" + msg.what);
            return;
        }
    }

    private void sendMessage() {
        Log.d(TAG, "sendMessage 1");
        final String subject = mSubject.getText().toString();
        if (mRecipients.isEmpty()) {
            Log.d(TAG, "sendMessage 2");
            final AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(getString(R.string.creator_no_recipients));
            builder.setPositiveButton(getString(R.string.alert_dialog_ok), null);
            builder.create().show();
            return;
        }
        Log.d(TAG, "sendMessage 3");
        
        setProgressBarIndeterminateVisibility(true);
        
        Message msg = new Message();
        msg.what = Consts.MESSAGE_SENDTEXT;
        msg.obj = subject;
        mHandler.sendMessage(msg);
    }

    private void remove(final Recipient recipient) {
        mRecipients.remove(recipient);
        mAdapter.remove(recipient);
        mAdapter.notifyDataSetChanged();
    }

    private void add(final Recipient recipient) {
        for (final Recipient recpt : mRecipients) {
            if (recpt.recipientId == recipient.recipientId) {
                Log.d(TAG, "Remove old recipient");
                remove(recpt);
            }
        }
        mRecipients.add(recipient);
        mAdapter.add(recipient);
        mAdapter.notifyDataSetChanged();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mRecipients;
    }

    public static byte[] getBytesFromFile(InputStream is) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();

            int nRead;
            byte[] data = new byte[16384];

            while ((nRead = is.read(data, 0, data.length)) != -1) {
                buffer.write(data, 0, nRead);
            }

            buffer.flush();

            return buffer.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, e.toString());
            return null;
        }
    }
    
    private void addInitialRecipients() {
        final String subject = getIntent().getStringExtra(INTENT_SUBJECT);
        final int recipient = getIntent().getIntExtra(INTENT_RECIPIENT, -1);
        final boolean isMail = getIntent().getBooleanExtra(INTENT_IS_MAIL, false);

        if (mReplyTo > 0) {
            new CopyRecipientsTask().execute(mReplyTo);
        }
        else if (isMail) {
            try {
                add(new Recipient(mKom.getApplicationContext(), mKom.getUserId(), mKom.getConferenceName(mKom.getUserId()), RecipientType.RECP_TO));
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                Log.d(TAG, "addInitialRecipients InterruptedException 1");
            }
            showAddRecipientDialog(RecipientType.RECP_TO, LookupType.LOOKUP_USERS);
        }
        else {
            showAddRecipientDialog(RecipientType.RECP_TO, LookupType.LOOKUP_BOTH);
        }

        if (subject != null) {
            mSubject.setText(subject);
        }
        if (recipient > 0) {
            try {
                add(new Recipient(mKom.getApplicationContext(), recipient, mKom.getConferenceName(recipient), RecipientType.RECP_TO));
            } catch (InterruptedException e) {
                // TODO Auto-generated catch block
                Log.d(TAG, "addInitialRecipients InterruptedException 2");
            }
        }
    }

    public void onServiceConnected(final ComponentName name, final IBinder service) {
        Log.d(TAG, "onServiceConnected 1");
        mKom = ((LocalBinder<KomServer>) service).getService();
        if (!getIntent().getBooleanExtra(INTENT_INITIAL_RECIPIENTS_ADDED, false)) {
            addInitialRecipients();
            getIntent().putExtra(INTENT_INITIAL_RECIPIENTS_ADDED, true);
        }
        enableButtons();
    }

    public void onServiceDisconnected(final ComponentName name) {
        Log.d(TAG, "onServiceDisconnected 1");
        mKom = null;
    }

    private App getApp() {
        return (App) getApplication();
    }

    byte[] imgdata = null;
    String imgFilename = null;
    
    private static Handler mHandler=null;
}
