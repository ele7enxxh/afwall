/**
 * Background service to spool nflog command output
 * 
 * Copyright (C) 2013 Kevin Cernekee
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 * @author Kevin Cernekee
 * @version 1.0
 */

package dev.ukanth.ufirewall.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import java.util.LinkedList;
import java.util.List;

import dev.ukanth.ufirewall.Api;
import dev.ukanth.ufirewall.log.Log;
import eu.chainfire.libsuperuser.Shell;
import eu.chainfire.libsuperuser.StreamGobbler;

public class NflogService extends Service {

	public static final String TAG = "AFWall";

	public static String nflogPath;
	public static final int queueNum = 40;

	private final IBinder mBinder = new Binder();
	private Shell.Interactive rootSession;

	private static final int MAX_ENTRIES = 128;
	private static LinkedList<String> circ = new LinkedList<String>();

	@Override
	public IBinder onBind(Intent intent) {
		return mBinder;
	}

	public void onCreate() {
		nflogPath = Api.getNflogPath(getApplicationContext());
		Log.d(TAG, "Starting " + nflogPath);

		rootSession = new Shell.Builder()
				.useSU()
				.setMinimalLogging(true)
				.setOnSTDOUTLineListener(new StreamGobbler.OnLineListener() {

					@Override
					public void onLine(String line) {
						synchronized (circ) {
							while (circ.size() >= MAX_ENTRIES) {
								circ.removeFirst();
							}
							circ.addLast(line);
						}
					}
				})

				.open(new Shell.OnCommandResultListener() {
					public void onCommandResult(int commandCode, int exitCode, List<String> output) {
						if (exitCode != 0) {
							Log.e(TAG, "Can't start nflog shell: exitCode " + exitCode);
							stopSelf();
						} else {
							Log.i(TAG, "nflog shell started");
							rootSession.addCommand(nflogPath + " " + queueNum);
						}
					}
				});
		}

	public static String fetchLogs() {
		StringBuilder sb = new StringBuilder();
		synchronized (circ) {
			for (int i = 0; i < circ.size(); i++) {
				String e = circ.get(i);
				sb.append(e + "\n");
			}
		}
		return sb.toString();
	}

	public static void clearLog() {
		synchronized (circ) {
			while (circ.size() > 0) {
				circ.remove();
			}
		}
	}

	public void onDestroy() {
		Log.e(TAG, "Received request to kill nflog");
		Thread thread = new Thread()
		{
		    @Override
		    public void run() {
				try {
					new RootShell.RootCommand().run(getApplicationContext(), Api.getBusyBoxPath(getApplicationContext(),false) + " pkill nflog");
				} catch (Exception e) {
					//another attempt to use killall command from system busybox
					try {
						new RootShell.RootCommand().run(getApplicationContext(), Api.getBusyBoxPath(getApplicationContext(),true) + " killall nflog");
					}catch(Exception ee) {
						// what if this also failed ? try using normal android way
						new RootShell.RootCommand().run(getApplicationContext(), "echo $(ps | grep nflog) | cut -d' ' -f2 | xargs kill");
						android.util.Log.e(TAG,ee.getMessage());
					}
				}

		    }
		};
		thread.start();
	}
}
