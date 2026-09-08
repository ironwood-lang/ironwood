// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.ide.eclipse;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.Preferences;

/**
 * Preference keys and access for the Ironwood plugin.
 *
 * <p>Kept separate from the preference page so that headless code, in
 * particular the language server launch, can read settings without dragging in
 * the workbench UI.
 */
public final class IronwoodPreferences {

    /** Preference node, matching the bundle symbolic name. */
    public static final String NODE = "ironwood.ide.eclipse";

    /** Absolute path of an Ironwood installation or source checkout. */
    public static final String IRONWOOD_HOME = "ironwoodHome";

    private IronwoodPreferences() {
    }

    public static Preferences node() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    public static String ironwoodHome() {
        return node().get(IRONWOOD_HOME, "").trim();
    }
}
