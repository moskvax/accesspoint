/**
 * Copyright 2015 Daniel Martí
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cc.mvdan.accesspoint;

import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

// WifiApControl provides control over Wi-Fi APs using the singleton pattern.
// Even though isSupported should be reliable, the underlying hidden APIs that
// are obtained via reflection to provide the main functionalities may not
// work as expected.
public class WifiApControl {

	private static final String TAG = "WifiApControl";

	private static Method getWifiApConfiguration;
	private static Method getWifiApState;
	private static Method isWifiApEnabled;
	private static Method setWifiApEnabled;

	static {
		for (Method method : WifiManager.class.getDeclaredMethods()) {
			switch (method.getName()) {
			case "getWifiApConfiguration":
				getWifiApConfiguration = method;
				break;
			case "getWifiApState":
				getWifiApState = method;
				break;
			case "isWifiApEnabled":
				isWifiApEnabled = method;
				break;
			case "setWifiApEnabled":
				setWifiApEnabled = method;
				break;
			}
		}
	}

	public static final int WIFI_AP_STATE_DISABLING = 10;
	public static final int WIFI_AP_STATE_DISABLED  = 11;
	public static final int WIFI_AP_STATE_ENABLING  = 12;
	public static final int WIFI_AP_STATE_ENABLED   = 13;
	public static final int WIFI_AP_STATE_FAILED    = 14;

	public static final int STATE_DISABLING = WIFI_AP_STATE_DISABLING;
	public static final int STATE_DISABLED  = WIFI_AP_STATE_DISABLED;
	public static final int STATE_ENABLING  = WIFI_AP_STATE_ENABLING;
	public static final int STATE_ENABLED   = WIFI_AP_STATE_ENABLED;
	public static final int STATE_FAILED    = WIFI_AP_STATE_FAILED;

	private static boolean isSoftwareSupported() {
		return (getWifiApState != null
				&& isWifiApEnabled != null
				&& setWifiApEnabled != null
				&& getWifiApConfiguration != null);
	}

	private static boolean isHardwareSupported() {
		// TODO: implement via native code
		return true;
	}

	// isSupported reports whether Wi-Fi APs are supported by this device.
	public static boolean isSupported() {
		return isSoftwareSupported() && isHardwareSupported();
	}

	private static final String fallbackWifiDevice = "wlan0";

	private final WifiManager wm;
	private final String wifiDevice;

	private static WifiApControl instance = null;

	private WifiApControl(final Context context) {
		wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
		wifiDevice = getWifiDeviceName(wm);
	}

	// getInstance is a standard singleton instance getter, constructing
	// the actual class when first called.
	public static WifiApControl getInstance(final Context context) {
		if (instance == null) {
			instance = new WifiApControl(context);
		}
		return instance;
	}

	// getInstance is a commodity singleton instance getter that doesn't
	// require a context granted that it has been provided at least once
	// before. Will return null if that is not the case.
	public static WifiApControl getInstance() {
		return instance;
	}

	private static String getWifiDeviceName(final WifiManager wifiManager) {
		if (Build.VERSION.SDK_INT < 9) {
			Log.w(TAG, "Older device - falling back to the default wifi device name: " + fallbackWifiDevice);
			return fallbackWifiDevice;
		}

		final WifiInfo wifiInfo = wifiManager.getConnectionInfo();
		final String wifiMacString = wifiInfo.getMacAddress();
		final byte[] wifiMacBytes = macAddressToByteArray(wifiMacString);
		final BigInteger wifiMac = new BigInteger(wifiMacBytes);

		try {
			Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
			while (ifaces.hasMoreElements()) {
				NetworkInterface iface = ifaces.nextElement();

				final byte[] hardwareAddress = iface.getHardwareAddress();
				if (hardwareAddress == null) {
					continue;
				}

				final BigInteger currentMac = new BigInteger(hardwareAddress);
				if (currentMac.equals(wifiMac)) {
					return iface.getName();
				}
			}
		} catch (IOException e) {
			Log.e(TAG, "", e);
		}

		Log.w(TAG, "None found - falling back to the default wifi device name: " + fallbackWifiDevice);
		return fallbackWifiDevice;
	}

	private static byte[] macAddressToByteArray(final String macString) {
		final String[] mac = macString.split("[:\\s-]");
		final byte[] macAddress = new byte[6];
		for (int i = 0; i < mac.length; i++) {
			macAddress[i] = Integer.decode("0x" + mac[i]).byteValue();
		}
		return macAddress;
	}

	// isWifiApEnabled returns whether the Wi-Fi AP is currently enabled.
	public boolean isWifiApEnabled() {
		try {
			return (Boolean) isWifiApEnabled.invoke(wm);
		} catch (Exception e) {
			Log.e(TAG, "", e);
		}
		return false;
	}

	// isEnabled is a commodity function alias for isWifiApEnabled.
	public boolean isEnabled() {
		return isWifiApEnabled();
	}

	// newStateNumber adapts the state constants to the current values in
	// the SDK. They were changed on 4.0 to have higher integer values.
	public static int newStateNumber(int state) {
		if (state < 10) {
			return state + 10;
		}
		return state;
	}

	// getWifiDeviceName returns the current Wi-Fi AP state.
	public int getWifiApState() {
		try {
			return newStateNumber((Integer) getWifiApState.invoke(wm));
		} catch (Exception e) {
			Log.e(TAG, "", e);
		}
		return -1;
	}

	// getState is a commodity function alias for getWifiApState.
	public int getState() {
		return getWifiApState();
	}

	// getWifiApConfiguration returns the current Wi-Fi AP configuration.
	public WifiConfiguration getWifiApConfiguration() {
		try {
			return (WifiConfiguration) getWifiApConfiguration.invoke(wm);
		} catch (Exception e) {
			Log.e(TAG, "", e);
		}
		return null;
	}

	// getConfiguration is a commodity function alias for
	// getWifiApConfiguration.
	public WifiConfiguration getConfiguration() {
		return getWifiApConfiguration();
	}

	// setWifiApEnabled starts a Wi-Fi AP with the specified
	// configuration. If one is already running, start using the new
	// configuration. You should call WifiManager.setWifiEnabled(false)
	// yourself before calling this method.
	public boolean setWifiApEnabled(WifiConfiguration config, boolean enabled) {
		try {
			return (Boolean) setWifiApEnabled.invoke(wm, config, enabled);
		} catch (Exception e) {
			Log.e(TAG, "", e);
		}
		return false;
	}

	// setEnabled is a commodity function alias for setWifiApEnabled.
	public boolean setEnabled(WifiConfiguration config, boolean enabled) {
		return setWifiApEnabled(config, enabled);
	}

	// enable starts the currently configured Wi-Fi AP.
	public boolean enable() {
		return setWifiApEnabled(getWifiApConfiguration(), true);
	}

	// disable stops any currently running Wi-Fi AP.
	public boolean disable() {
		return setWifiApEnabled(null, false);
	}

	// getInetAddress returns the IP address that the device has in its
	// own Wi-Fi AP local network. Will return null if no Wi-Fi AP is
	// currently enabled.
	public InetAddress getInetAddress() {
		if (!isEnabled()) {
			return null;
		}

		try {
			Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
			while (ifaces.hasMoreElements()) {
				NetworkInterface iface = ifaces.nextElement();

				Enumeration<InetAddress> addrs = iface.getInetAddresses();
				while (addrs.hasMoreElements()) {
					InetAddress addr = addrs.nextElement();

					if (addr.isLoopbackAddress()) {
						continue;
					}

					final String ifaceName = iface.getDisplayName();
					if (ifaceName.contains(wifiDevice)) {
						return addr;
					}
				}
			}

		} catch (IOException e) {
			Log.e(TAG, "", e);
		}
		return null;
	}

	// Client describes a Wi-Fi AP device connected to the network.
	public static class Client {

		// IPAddr is the raw string of the IP Address client
		public String IPAddr;

		// HWAddr is the raw string of the MAC of the client
		public String HWAddr;

		public Client(String IPAddr, String HWAddr) {
			this.IPAddr = IPAddr;
			this.HWAddr = HWAddr;
		}
	}

	// getClients returns a list of all clients connected to the network.
	// Since the information is pulled from ARP, which is cached for up to
	// five minutes, this method may yield clients that disconnected
	// minutes ago.
	public List<Client> getClients() {
		if (!isEnabled()) {
			return null;
		}
		final List<Client> result = new ArrayList<>();

		// Basic sanity checks
		final Pattern macPattern = Pattern.compile("..:..:..:..:..:..");

		BufferedReader br = null;
		try {
			br = new BufferedReader(new FileReader("/proc/net/arp"));
			String line;
			while ((line = br.readLine()) != null) {
				final String[] parts = line.split(" +");
				if (parts.length < 6) {
					continue;
				}

				final String IPAddr = parts[0];
				final String HWAddr = parts[3];
				final String device = parts[5];

				if (!device.equals(wifiDevice)) {
					continue;
				}

				if (!macPattern.matcher(parts[3]).find()) {
					continue;
				}

				result.add(new Client(IPAddr, HWAddr));
			}
		} catch (IOException e) {
			Log.e(TAG, "", e);
		} finally {
			try {
				if (br != null) {
					br.close();
				}
			} catch (IOException e) {
				Log.e(TAG, "", e);
			}
		}

		return result;
	}

	// ReachableClientListener is an interface to collect the results
	// provided by getReachableClients via callbacks.
	public interface ReachableClientListener {

		// Function called each time a reachable client is found.
		void onReachableClient(Client c);
	}

	// getReachableClients fetches the clients connected to the network
	// much like getClients, but only those which are reachable. Since
	// checking for reachability requires network I/O, the results are
	// provided via callbacks.
	public void getReachableClients(final ReachableClientListener listener,
			final int timeout) {
		final List<Client> clients = getClients();
		if (clients == null) {
			return;
		}
		final ExecutorService es = Executors.newCachedThreadPool();
		for (final Client c : clients) {
			es.submit(new Runnable() {
				public void run() {
					try {
						final InetAddress ip = InetAddress.getByName(c.IPAddr);
						if (ip.isReachable(timeout)) {
							listener.onReachableClient(c);
						}
					} catch (IOException e) {
						Log.e(TAG, "", e);
					}
				}
			});
		}
	}

	// getReachableClients returns a list of the clients connected to the
	// network which are reachable. Like getReachableClients, but the
	// function doesn't return until all tasks are done and it returns the
	// results all in one. Easier to use, but beware of blocking your UI
	// thread.
	public List<Client> getReachableClientsList(final int timeout) {
		final List<Client> clients = getClients();
		if (clients == null) {
			return null;
		}
		final ExecutorService es = Executors.newCachedThreadPool();
		final List<Callable<Client>> tasks = new ArrayList<>(clients.size());
		for (final Client c : clients) {
			tasks.add(new Callable<Client>() {
				public Client call() {
					try {
						final InetAddress ip = InetAddress.getByName(c.IPAddr);
						if (ip.isReachable(timeout)) {
							return c;
						}
					} catch (IOException e) {
						Log.e(TAG, "", e);
					}
					return null;
				}
			});
		}

		final List<Client> result = new ArrayList<>();
		try {
			for (final Future<Client> answer : es.invokeAll(tasks)) {
				final Client client = answer.get();
				if (client == null) {
					continue;
				}
				result.add(client);
			}
		} catch (InterruptedException | ExecutionException e) {
			Log.e(TAG, "", e);
			return null;
		}
		return result;
	}

}
