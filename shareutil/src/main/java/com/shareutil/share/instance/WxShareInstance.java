package com.shareutil.share.instance;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Pair;

import com.shareutil.ShareUtil;
import com.shareutil.share.ImageDecoder;
import com.shareutil.share.ShareImageObject;
import com.shareutil.share.ShareListener;
import com.shareutil.share.SharePlatform;
import com.tencent.mm.opensdk.modelbase.BaseReq;
import com.tencent.mm.opensdk.modelbase.BaseResp;
import com.tencent.mm.opensdk.modelmsg.SendMessageToWX;
import com.tencent.mm.opensdk.modelmsg.WXImageObject;
import com.tencent.mm.opensdk.modelmsg.WXMediaMessage;
import com.tencent.mm.opensdk.modelmsg.WXMiniProgramObject;
import com.tencent.mm.opensdk.modelmsg.WXTextObject;
import com.tencent.mm.opensdk.modelmsg.WXWebpageObject;
import com.tencent.mm.opensdk.openapi.IWXAPI;
import com.tencent.mm.opensdk.openapi.IWXAPIEventHandler;
import com.tencent.mm.opensdk.openapi.WXAPIFactory;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.FlowableOnSubscribe;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.annotations.NonNull;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

/**
 * desc：微信分享</br>
 * author：Leo </br>
 */
public class WxShareInstance implements ShareInstance {

    /**
     * 微信分享限制thumb image必须小于32Kb，否则点击分享会没有反应
     */
    private IWXAPI mIWXAPI;

    private Disposable mShareFunc;
    private Disposable mShareImage;

    private static final int THUMB_SIZE = 32 * 1024;
    private static final int TARGET_SIZE = 200;

    public WxShareInstance(Context context, String appId) {
        mIWXAPI = WXAPIFactory.createWXAPI(context, appId, true);
        mIWXAPI.registerApp(appId);
    }

    @Override
    public void shareText(int platform, String text, Activity activity, ShareListener listener) {
        if (listener != null) {
            listener.shareStart();
        }
        WXTextObject textObject = new WXTextObject();
        textObject.text = text;

        WXMediaMessage message = new WXMediaMessage();
        message.mediaObject = textObject;
        message.description = text;

        sendMessage(platform, message, buildTransaction("text"));
    }

    @Override
    public void shareMedia(final int platform, final String title, final String targetUrl, final String summary, final String miniId, final String miniPath, final ShareImageObject shareImageObject, final Activity activity, final ShareListener listener) {
        shareFunc(platform, title, targetUrl, summary, miniId, miniPath, shareImageObject, activity, listener);
    }

    @Override
    public void shareMedia(int platform, String title, String targetUrl, String summary
            , String miniId, String miniPath, ShareImageObject shareImageObject
            , boolean shareImmediate, Activity activity, ShareListener listener) {
        // 直接分享，外部处理好分享图片
        if (shareImmediate) {
            if (shareImageObject.getBytes() != null) {
                handleShareWx(platform, title, targetUrl, summary
                        , shareImageObject.getBytes(), miniId, miniPath, listener);
            }
        } else {
            shareFunc(platform, title, targetUrl, summary, miniId, miniPath
                    , shareImageObject, activity, listener);
        }
    }

    @SuppressLint("CheckResult")
    private void shareFunc(final int platform, final String title, final String targetUrl
            , final String summary, final String miniId, final String miniPath
            , final ShareImageObject shareImageObject
            , final Activity activity, final ShareListener listener) {

        mShareFunc = Flowable.create(new FlowableOnSubscribe<byte[]>() {
            @Override
            public void subscribe(@NonNull FlowableEmitter<byte[]> emitter) {
                try {
                    String imagePath = ImageDecoder.decode(activity, shareImageObject);
                    emitter.onNext(ImageDecoder.compress2Byte(imagePath, TARGET_SIZE, THUMB_SIZE));
                } catch (Exception e) {
                    emitter.onError(e);
                }
            }
        }, BackpressureStrategy.DROP)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe(new Consumer<byte[]>() {
                    @Override
                    public void accept(@NonNull byte[] bytes) {
                        handleShareWx(platform, title, targetUrl, summary
                                , bytes, miniId, miniPath, listener);
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(@NonNull Throwable throwable) {
                        listener.shareFailure(new Exception(throwable));
                        recycle();
                        activity.finish();
                    }
                });
    }

    private void handleShareWx(final int platform, final String title, final String targetUrl
            , final String summary, byte[] bytes, final String miniId
            , final String miniPath, ShareListener shareListener) {

        if (shareListener != null) {
            shareListener.shareStart();
        }
        WXMediaMessage message;
        WXMiniProgramObject miniProgramObject = null;
        WXWebpageObject webpageObject = null;

        if (miniId != null && !"".equals(miniId) && miniPath != null && !"".equals(miniPath)) {
            miniProgramObject = new WXMiniProgramObject();
            // 低版本微信将打开网页分享
            miniProgramObject.webpageUrl = targetUrl;
            // 目标小程序的原始ID
            miniProgramObject.userName = miniId;
            // 小程序path
            miniProgramObject.path = miniPath;
        } else {
            webpageObject = new WXWebpageObject();
            webpageObject.webpageUrl = targetUrl;
        }

        message = new WXMediaMessage(miniProgramObject == null ? webpageObject : miniProgramObject);
        message.title = title;
        message.description = summary;
        message.thumbData = bytes;

        sendMessage(platform, message, buildTransaction("webPage"));
    }

    @SuppressLint("CheckResult")
    @Override
    public void shareImage(final int platform, final ShareImageObject shareImageObject,
                           final Activity activity, final ShareListener listener) {

        mShareImage = Flowable.create(new FlowableOnSubscribe<Pair<Bitmap, byte[]>>() {
            @Override
            public void subscribe(@NonNull FlowableEmitter<Pair<Bitmap, byte[]>> emitter) {
                try {
                    String imagePath = ImageDecoder.decode(activity, shareImageObject);
                    emitter.onNext(Pair.create(BitmapFactory.decodeFile(imagePath),
                            ImageDecoder.compress2Byte(imagePath, TARGET_SIZE, THUMB_SIZE)));
                } catch (Exception e) {
                    emitter.onError(e);
                }
            }
        }, BackpressureStrategy.DROP)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Consumer<Pair<Bitmap, byte[]>>() {
                    @Override
                    public void accept(@NonNull Pair<Bitmap, byte[]> pair) {
                        WXImageObject imageObject = new WXImageObject(pair.first);

                        WXMediaMessage message = new WXMediaMessage();
                        message.mediaObject = imageObject;
                        message.thumbData = pair.second;

                        sendMessage(platform, message, buildTransaction("image"));
                    }
                }, new Consumer<Throwable>() {
                    @Override
                    public void accept(@NonNull Throwable throwable) {
                        listener.shareFailure(new Exception(throwable));
                        recycle();
                        activity.finish();
                    }
                });
    }

    @Override
    public void handleResult(int requestCode, int resultCode, Intent data) {
        if (mIWXAPI == null) {
            return;
        }

        mIWXAPI.handleIntent(data, new IWXAPIEventHandler() {
            @Override
            public void onReq(BaseReq baseReq) {
            }

            @Override
            public void onResp(BaseResp baseResp) {
                switch (baseResp.errCode) {
                    case BaseResp.ErrCode.ERR_OK:
                        ShareUtil.mShareListener.shareSuccess();
                        ShareUtil.recycle();
                        break;
                    case BaseResp.ErrCode.ERR_USER_CANCEL:
                        ShareUtil.mShareListener.shareCancel();
                        ShareUtil.recycle();
                        break;
                    default:
                        ShareUtil.mShareListener.shareFailure(new Exception(baseResp.errStr));
                        ShareUtil.recycle();
                        break;
                }

                recycle();
            }
        });
    }

    @Override
    public boolean isInstall(Context context) {
        return mIWXAPI.isWXAppInstalled();
    }

    private void sendMessage(int platform, WXMediaMessage message, String transaction) {
        SendMessageToWX.Req req = new SendMessageToWX.Req();
        req.transaction = transaction;
        req.message = message;

        // 小程序类型分享到会话区域 与SharePlatform.WX一致
        req.scene = platform == SharePlatform.WX_TIMELINE ? SendMessageToWX.Req.WXSceneTimeline
                : SendMessageToWX.Req.WXSceneSession;
        mIWXAPI.sendReq(req);
        recycle();
    }

    private String buildTransaction(String type) {
        return System.currentTimeMillis() + type;
    }

    @Override
    public void recycle() {
        if (mShareFunc != null && !mShareFunc.isDisposed()) {
            mShareFunc.dispose();
            mShareFunc = null;
        }
        if (mShareImage != null && !mShareImage.isDisposed()) {
            mShareImage.dispose();
            mShareImage = null;
        }

        if (mIWXAPI != null) {
            mIWXAPI.detach();
            mIWXAPI = null;
        }
    }
}
