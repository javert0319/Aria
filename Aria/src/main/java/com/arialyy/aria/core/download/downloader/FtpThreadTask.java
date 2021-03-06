/*
 * Copyright (C) 2016 AriaLyy(https://github.com/AriaLyy/Aria)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arialyy.aria.core.download.downloader;

import com.arialyy.aria.core.AriaManager;
import com.arialyy.aria.core.common.AbsFtpThreadTask;
import com.arialyy.aria.core.common.StateConstance;
import com.arialyy.aria.core.common.SubThreadConfig;
import com.arialyy.aria.core.download.DownloadEntity;
import com.arialyy.aria.core.download.DownloadTaskEntity;
import com.arialyy.aria.core.inf.IDownloadListener;
import com.arialyy.aria.util.ALog;
import com.arialyy.aria.util.BufferedRandomAccessFile;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

/**
 * Created by Aria.Lao on 2017/7/24.
 * Ftp下载任务
 */
class FtpThreadTask extends AbsFtpThreadTask<DownloadEntity, DownloadTaskEntity> {
  private final String TAG = "FtpThreadTask";
  private boolean isOpenDynamicFile;

  FtpThreadTask(StateConstance constance, IDownloadListener listener,
      SubThreadConfig<DownloadTaskEntity> downloadInfo) {
    super(constance, listener, downloadInfo);
    AriaManager manager = AriaManager.getInstance(AriaManager.APP);
    mConnectTimeOut = manager.getDownloadConfig().getConnectTimeOut();
    mReadTimeOut = manager.getDownloadConfig().getIOTimeOut();
    mBufSize = manager.getDownloadConfig().getBuffSize();
    isNotNetRetry = manager.getDownloadConfig().isNotNetRetry();
    isOpenDynamicFile = STATE.isOpenDynamicFile;
    setMaxSpeed(manager.getDownloadConfig().getMaxSpeed());
  }

  @Override public void run() {
    //当前子线程的下载位置
    mChildCurrentLocation = mConfig.START_LOCATION;
    FTPClient client = null;
    InputStream is = null;

    try {
      ALog.d(TAG,
          String.format("任务【%s】线程__%s__开始下载【开始位置 : %s，结束位置：%s】", mConfig.TEMP_FILE.getName(),
              mConfig.THREAD_ID, mConfig.START_LOCATION, mConfig.END_LOCATION));
      client = createClient();
      if (client == null) return;
      if (mConfig.START_LOCATION > 0) {
        client.setRestartOffset(mConfig.START_LOCATION);
      }
      //发送第二次指令时，还需要再做一次判断
      int reply = client.getReplyCode();
      if (!FTPReply.isPositivePreliminary(reply) && reply != FTPReply.COMMAND_OK) {
        fail(mChildCurrentLocation,
            String.format("获取文件信息错误，错误码为：%s，msg：%s", reply, client.getReplyString()),
            null);
        client.disconnect();
        return;
      }
      String remotePath =
          new String(mTaskEntity.getUrlEntity().remotePath.getBytes(charSet), SERVER_CHARSET);
      ALog.i(TAG, String.format("remotePath【%s】", remotePath));
      is = client.retrieveFileStream(remotePath);
      reply = client.getReplyCode();
      if (!FTPReply.isPositivePreliminary(reply)) {
        fail(mChildCurrentLocation,
            String.format("获取流失败，错误码为：%s，msg：%s", reply, client.getReplyString()),
            null);
        client.disconnect();
        return;
      }

      if (isOpenDynamicFile) {
        readDynamicFile(is);
      } else {
        readNormal(is);
      }

      if (isBreak()) {
        return;
      }
      ALog.i(TAG,
          String.format("任务【%s】线程__%s__下载完毕", mConfig.TEMP_FILE.getName(), mConfig.THREAD_ID));
      writeConfig(true, mConfig.END_LOCATION);
      STATE.COMPLETE_THREAD_NUM++;
      if (STATE.isComplete()) {
        STATE.TASK_RECORD.deleteData();
        STATE.isRunning = false;
        mListener.onComplete();
      }
      if (STATE.isFail()) {
        STATE.isRunning = false;
        mListener.onFail(false);
      }
    } catch (IOException e) {
      fail(mChildCurrentLocation, String.format("下载失败【%s】", mConfig.URL), e);
    } catch (Exception e) {
      fail(mChildCurrentLocation, "获取流失败", e);
    } finally {
      try {
        if (is != null) {
          is.close();
        }
        if (client != null && client.isConnected()) {
          client.disconnect();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * 动态长度文件读取方式
   */
  private void readDynamicFile(InputStream is) {
    FileOutputStream fos = null;
    FileChannel foc = null;
    ReadableByteChannel fic = null;
    try {
      int len;
      fos = new FileOutputStream(mConfig.TEMP_FILE, true);
      foc = fos.getChannel();
      fic = Channels.newChannel(is);
      ByteBuffer bf = ByteBuffer.allocate(mBufSize);
      while ((len = fic.read(bf)) != -1) {
        if (isBreak()) {
          break;
        }
        if (mSleepTime > 0) {
          Thread.sleep(mSleepTime);
        }
        bf.flip();
        foc.write(bf);
        bf.compact();
        progress(len);
      }
    } catch (InterruptedException e) {
      e.printStackTrace();
    } catch (IOException e) {
      fail(mChildCurrentLocation, String.format("下载失败【%s】", mConfig.URL), e);
    } finally {
      try {
        if (fos != null) {
          fos.close();
        }
        if (foc != null) {
          foc.close();
        }
        if (fic != null) {
          fic.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }

  /**
   * 多线程写文件方式
   */
  private void readNormal(InputStream is) {
    BufferedRandomAccessFile file = null;
    try {
      file = new BufferedRandomAccessFile(mConfig.TEMP_FILE, "rwd", mBufSize);
      file.seek(mConfig.START_LOCATION);
      byte[] buffer = new byte[mBufSize];
      int len;
      while ((len = is.read(buffer)) != -1) {
        if (isBreak()) {
          break;
        }
        if (mSleepTime > 0) Thread.sleep(mSleepTime);
        if (mChildCurrentLocation + len >= mConfig.END_LOCATION) {
          len = (int) (mConfig.END_LOCATION - mChildCurrentLocation);
          file.write(buffer, 0, len);
          progress(len);
          break;
        } else {
          file.write(buffer, 0, len);
          progress(len);
        }
      }
    } catch (IOException e) {
      fail(mChildCurrentLocation, String.format("下载失败【%s】", mConfig.URL), e);
    } catch (InterruptedException e) {
      e.printStackTrace();
    } finally {
      try {
        if (file != null) {
          file.close();
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    }
  }
}
