/*
 * This file is part of Sui.
 *
 * Sui is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Sui is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Sui.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (c) 2021 Sui Contributors
 */

package rikka.sui.server.bridge;

import android.os.IBinder;
import android.os.Parcel;
import android.os.ServiceManager;

import java.lang.reflect.Field;
import java.util.Map;

import rikka.sui.server.SuiService;

import static rikka.sui.server.ServerConstants.LOGGER;

public class BridgeServiceClient {

    private static final int BRIDGE_TRANSACTION_CODE = ('_' << 24) | ('S' << 16) | ('U' << 8) | 'I';
    private static final String BRIDGE_SERVICE_DESCRIPTOR = "android.app.IActivityManager";
    private static final String BRIDGE_SERVICE_NAME = "activity";

    private static final int ACTION_SEND_BINDER = 1;
    private static final int ACTION_GET_BINDER = ACTION_SEND_BINDER + 1;
    private static final int ACTION_NOTIFY_FINISHED = ACTION_SEND_BINDER + 2;

    private static class DeathRecipient implements IBinder.DeathRecipient {

        private final IBinder binder;

        public DeathRecipient(IBinder binder) {
            this.binder = binder;
        }

        @Override
        public void binderDied() {
            binder.unlinkToDeath(this, 0);

            LOGGER.i("service %s is dead.", BRIDGE_SERVICE_NAME);

            try {
                //noinspection JavaReflectionMemberAccess
                Field field = ServiceManager.class.getDeclaredField("sServiceManager");
                field.setAccessible(true);
                field.set(null, null);

                //noinspection JavaReflectionMemberAccess
                field = ServiceManager.class.getDeclaredField("sCache");
                field.setAccessible(true);
                Object sCache = field.get(null);
                if (sCache instanceof Map) {
                    //noinspection rawtypes
                    ((Map) sCache).clear();
                }
                LOGGER.i("clear ServiceManager");
            } catch (Throwable e) {
                LOGGER.w(e, "clear ServiceManager");
            }

            sendToBridge(true);
        }
    }

    public interface Listener {

        void onSystemServerRestarted();

        void onResponseFromBridgeService(boolean response);
    }

    private static Listener listener;

    private static void sendToBridge(boolean isRestart) {
        IBinder bridgeService;
        do {
            bridgeService = ServiceManager.getService(BRIDGE_SERVICE_NAME);
            if (bridgeService != null && bridgeService.pingBinder()) {
                break;
            }

            LOGGER.i("service %s is not started, wait 1s.", BRIDGE_SERVICE_NAME);

            try {
                //noinspection BusyWait
                Thread.sleep(1000);
            } catch (Throwable e) {
                LOGGER.w("sleep", e);
            }
        } while (true);

        if (isRestart && listener != null) {
            listener.onSystemServerRestarted();
        }

        try {
            bridgeService.linkToDeath(new DeathRecipient(bridgeService), 0);
        } catch (Throwable e) {
            LOGGER.w(e, "linkToDeath");
            sendToBridge(false);
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        boolean res = false;
        for (int i = 0; i < 3; i++) {
            try {
                data.writeInterfaceToken(BRIDGE_SERVICE_DESCRIPTOR);
                data.writeInt(ACTION_SEND_BINDER);
                IBinder binder = SuiService.getInstance();
                LOGGER.v("binder %s", binder);
                data.writeStrongBinder(binder);
                res = bridgeService.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0);
                reply.readException();
            } catch (Throwable e) {
                LOGGER.e(e, "send binder");
            } finally {
                data.recycle();
                reply.recycle();
            }

            if (res) break;

            LOGGER.w("no response from bridge, retry in 1s");

            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
            }
        }

        if (listener != null) {
            listener.onResponseFromBridgeService(res);
        }
    }

    public static void send(Listener listener) {
        BridgeServiceClient.listener = listener;
        sendToBridge(false);
    }

    public static void notifyStarted() {
        IBinder bridgeService = ServiceManager.getService(BRIDGE_SERVICE_NAME);
        if (bridgeService == null) {
            return;
        }

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        boolean res = false;
        try {
            data.writeInterfaceToken(BRIDGE_SERVICE_DESCRIPTOR);
            data.writeInt(ACTION_NOTIFY_FINISHED);
            res = bridgeService.transact(BRIDGE_TRANSACTION_CODE, data, reply, 0);
            reply.readException();
        } catch (Throwable e) {
            LOGGER.e(e, "notify started");
        } finally {
            data.recycle();
            reply.recycle();
        }

        if (res) {
            LOGGER.i("notify started");
        } else {
            LOGGER.w("notify started");
        }
    }
}
