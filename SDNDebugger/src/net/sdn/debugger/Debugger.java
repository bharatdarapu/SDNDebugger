/*
 * Copyright 2014 Netflix, Inc.
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

package net.sdn.debugger;

public final class Debugger {

	public static final int DEFAULT_PROXY_PORT = 8100;
	public static final int DEFAULT_MONITOR_PORT = 8200;

    public static void main(final String[] args) {
        //new Thread(new ProxyHandler(DEFAULT_PROXY_PORT)).start();
        //new Thread(new MonitorHandler(DEFAULT_MONITOR_PORT)).start();
        new Thread(new StatefulFirewallMonitorHandler(DEFAULT_MONITOR_PORT)).start();
    }
}