// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.lsp;

import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Entry point for the Ironwood language server, speaking the protocol over
 * standard input and output.
 *
 * <p>Standard output carries protocol traffic and nothing else. Anything the
 * compiler front end or the server prints would corrupt that stream, so
 * {@code System.out} is redirected to {@code System.err} before the launcher
 * starts. The host reads standard error separately and routes it to its log.
 */
public final class IronwoodLanguageServerMain {

    private IronwoodLanguageServerMain() {
    }

    public static void main(String[] args) throws Exception {
        InputStream protocolIn = System.in;
        OutputStream protocolOut = System.out;

        PrintStream diagnosticsOut = System.err;
        System.setOut(diagnosticsOut);

        IronwoodLanguageServer server = new IronwoodLanguageServer();
        Launcher<LanguageClient> launcher =
                LSPLauncher.createServerLauncher(server, protocolIn, protocolOut);
        server.connect(launcher.getRemoteProxy());
        launcher.startListening().get();
    }
}
