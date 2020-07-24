/*
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
 *
 * Copyright 2013-2015 Redwarp
 */
package net.redwarp.tool.resizer.misc;

import java.util.Locale;
import java.util.ResourceBundle;

public class Configuration {

  private static ResourceBundle bundle = ResourceBundle.getBundle(
      "misc.configuration", Locale.KOREA);
  private static Settings settings = null;

  public static String getVersion() {
    return bundle.getString("versionName");
  }

  public static int getVersionCode() {
    return Integer.valueOf(bundle.getString("versionCode"));
  }

  public static Settings getSettings() {
    if (settings == null) {
      settings = new Settings();
    }
    return settings;
  }
}
