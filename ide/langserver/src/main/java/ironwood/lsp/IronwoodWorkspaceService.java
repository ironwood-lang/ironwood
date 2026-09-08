// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.services.WorkspaceService;

/**
 * Workspace-level notifications.
 *
 * <p>The server currently derives everything it needs from the files the editor
 * has open, so there is no configuration to apply and no watched-file handling
 * to do. The methods are required by the interface and are intentionally empty
 * rather than absent.
 */
public final class IronwoodWorkspaceService implements WorkspaceService {

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        // No server settings yet.
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // Analysis reads the source set fresh on each run, so a file changed
        // outside the editor is picked up by the next analysis without needing
        // to be tracked here.
    }
}
