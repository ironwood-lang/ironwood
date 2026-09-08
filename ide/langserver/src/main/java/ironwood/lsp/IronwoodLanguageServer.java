// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.concurrent.CompletableFuture;

/**
 * The Ironwood language server.
 *
 * <p>It advertises only what it actually implements. Diagnostics need no
 * capability flag beyond document synchronization, so the current capability
 * set is deliberately small and grows as features land.
 */
public final class IronwoodLanguageServer implements LanguageServer, LanguageClientAware {

    private final IronwoodTextDocumentService textDocumentService =
            new IronwoodTextDocumentService();
    private final IronwoodWorkspaceService workspaceService = new IronwoodWorkspaceService();

    /** Set by {@link #exit()} so the launcher can report the right status. */
    private volatile int exitCode = 1;

    @Override
    public void connect(LanguageClient client) {
        textDocumentService.connect(client);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        // Full synchronization keeps the server's copy of a buffer trivially
        // correct. Incremental synchronization is an optimization worth making
        // only once analysis itself is incremental.
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        // The outline comes from the syntax tree, so it is available even for a
        // file that does not yet analyze cleanly.
        capabilities.setDocumentSymbolProvider(true);
        capabilities.setHoverProvider(true);
        capabilities.setDefinitionProvider(true);

        InitializeResult result = new InitializeResult(capabilities);
        result.setServerInfo(new ServerInfo("Ironwood Language Server", serverVersion()));
        return CompletableFuture.completedFuture(result);
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        textDocumentService.shutdown();
        exitCode = 0;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        System.exit(exitCode);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    private static String serverVersion() {
        String version = IronwoodLanguageServer.class.getPackage().getImplementationVersion();
        return version == null ? "development" : version;
    }
}
