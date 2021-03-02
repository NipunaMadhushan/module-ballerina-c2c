/*
 *  Copyright (c) 2020, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package io.ballerina.c2c.tooling.completion;

import io.ballerina.c2c.tooling.toml.CommonUtil;
import io.ballerina.compiler.api.SemanticModel;
import io.ballerina.compiler.api.symbols.Symbol;
import io.ballerina.compiler.syntax.tree.ImportDeclarationNode;
import io.ballerina.compiler.syntax.tree.ModulePartNode;
import io.ballerina.compiler.syntax.tree.SyntaxTree;
import io.ballerina.projects.Document;
import io.ballerina.projects.Module;
import io.ballerina.toml.syntax.tree.Node;
import io.ballerina.toml.syntax.tree.NonTerminalNode;
import io.ballerina.tools.text.LinePosition;
import org.ballerinalang.langserver.commons.CompletionContext;
import org.ballerinalang.langserver.commons.DocumentServiceContext;
import org.ballerinalang.langserver.commons.LSOperation;
import org.ballerinalang.langserver.commons.LanguageServerContext;
import org.ballerinalang.langserver.commons.workspace.WorkspaceManager;
import org.eclipse.lsp4j.CompletionCapabilities;
import org.eclipse.lsp4j.Position;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;

/**
 * Language server context implementation.
 *
 * @since 1.2.0
 */
public class CloudCompletionContextImpl implements TomlCompletionContext, DocumentServiceContext {

    private final LSOperation operation;
    private final Path filePath;
    private final String fileUri;
    private final WorkspaceManager workspaceManager;
    private List<Symbol> visibleSymbols;
    private List<ImportDeclarationNode> currentDocImports;
    private final LanguageServerContext languageServerContext;
    private final CompletionCapabilities capabilities;
    private final Position cursorPosition;
    private int cursorPosInTree = -1;
    private final List<Node> resolverChain = new ArrayList<>();
    private NonTerminalNode nodeAtCursor;

    CloudCompletionContextImpl(CompletionContext context, LanguageServerContext serverContext) {
        this.operation = context.operation();
        this.fileUri = context.fileUri();
        this.workspaceManager = context.workspace();
        this.capabilities = context.getCapabilities();
        this.cursorPosition = context.getCursorPosition();
        this.languageServerContext = serverContext;
        Optional<Path> optFilePath = CommonUtil.getPathFromURI(this.fileUri);
        if (optFilePath.isEmpty()) {
            throw new RuntimeException("Invalid file uri: " + this.fileUri);
        }
        this.filePath = optFilePath.get();
    }

    /**
     * Get the file uri.
     *
     * @return {@link String} file uri
     */
    public String fileUri() {
        return this.fileUri;
    }

    /**
     * Get the file path.
     *
     * @return {@link Path} file path
     */
    @Nonnull
    public Path filePath() {
        return this.filePath;
    }

    @Override
    public LSOperation operation() {
        return this.operation;
    }

    @Override
    public List<Symbol> visibleSymbols(Position position) {
        if (this.visibleSymbols == null) {
            Optional<SemanticModel> semanticModel = this.workspaceManager.semanticModel(this.filePath);
            Optional<Document> srcFile = this.workspaceManager.document(filePath);

            if (semanticModel.isEmpty() || srcFile.isEmpty()) {
                return Collections.emptyList();
            }

            visibleSymbols = semanticModel.get().visibleSymbols(srcFile.get(),
                    LinePosition.from(position.getLine(),
                            position.getCharacter()));
        }

        return visibleSymbols;
    }

    @Override
    public WorkspaceManager workspace() {
        return this.workspaceManager;
    }

    @Override
    public List<ImportDeclarationNode> currentDocImports() {
        if (this.currentDocImports == null) {
            Optional<Document> document = this.workspace().document(this.filePath);
            if (document.isEmpty()) {
                throw new RuntimeException("Cannot find a valid document");
            }
            this.currentDocImports = ((ModulePartNode) document.get().syntaxTree().rootNode()).imports().stream()
                    .collect(Collectors.toList());
        }

        return this.currentDocImports;
    }

    @Override
    public Optional<Document> currentDocument() {
        return this.workspace().document(this.filePath());
    }

    @Override
    public Optional<Module> currentModule() {
        return this.workspaceManager.module(this.filePath);
    }

    @Override
    public Optional<SemanticModel> currentSemanticModel() {
        return this.workspaceManager.semanticModel(this.filePath);
    }

    @Override
    public Optional<SyntaxTree> currentSyntaxTree() {
        return this.workspaceManager.syntaxTree(this.filePath);
    }

    @Override
    public LanguageServerContext languageServercontext() {
        return this.languageServerContext;
    }

    @Override
    public CompletionCapabilities getCapabilities() {
        return this.capabilities;
    }

    @Override
    public void setCursorPositionInTree(int offset) {
        if (this.cursorPosInTree > -1) {
            throw new RuntimeException("Setting the cursor offset more than once is not allowed");
        }
        this.cursorPosInTree = offset;
    }

    @Override
    public int getCursorPositionInTree() {
        return this.cursorPosInTree;
    }

    @Override
    public Position getCursorPosition() {
        return this.cursorPosition;
    }

    public void setNodeAtCursor(NonTerminalNode node) {
        if (this.nodeAtCursor != null) {
            throw new RuntimeException("Setting the node more than once is not allowed");
        }
        this.nodeAtCursor = node;
    }

    public NonTerminalNode getNodeAtCursor() {
        return this.nodeAtCursor;
    }

    public void addResolver(Node node) {
        this.resolverChain.add(node);
    }

    public List<Node> getResolverChain() {
        return this.resolverChain;
    }
}
