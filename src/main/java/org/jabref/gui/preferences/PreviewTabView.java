package org.jabref.gui.preferences;

import java.util.List;

import javax.inject.Inject;

import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.VBox;

import org.jabref.Globals;
import org.jabref.gui.DialogService;
import org.jabref.gui.DragAndDropDataFormats;
import org.jabref.gui.actions.ActionFactory;
import org.jabref.gui.actions.SimpleCommand;
import org.jabref.gui.actions.StandardActions;
import org.jabref.gui.preview.PreviewViewer;
import org.jabref.gui.util.BindingsHelper;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.gui.util.ViewModelListCellFactory;
import org.jabref.logic.citationstyle.CitationStylePreviewLayout;
import org.jabref.logic.citationstyle.PreviewLayout;
import org.jabref.logic.citationstyle.TextBasedPreviewLayout;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.util.TestEntry;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.preferences.JabRefPreferences;

import com.airhacks.afterburner.views.ViewLoader;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PreviewTabView extends VBox implements PrefsTab {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewTabView.class);

    @FXML private ListView<PreviewLayout> availableListView;
    @FXML private ListView<PreviewLayout> chosenListView;

    @FXML private Button toRightButton;
    @FXML private Button toLeftButton;
    @FXML private Button sortUpButton;
    @FXML private Button sortDownButton;

    @FXML private Label readOnlyLabel;
    @FXML private Button resetDefaultButton;

    @FXML private ScrollPane previewPane;
    @FXML private CodeArea editArea;

    private final ContextMenu contextMenu;

    @Inject private TaskExecutor taskExecutor;
    @Inject private DialogService dialogService;
    private final JabRefPreferences preferences;

    private ListView<PreviewLayout> dragSourceList = null;

    private long lastKeyPressTime;
    private String listSearchTerm;

    private PreviewTabViewModel viewModel;

    private class EditAction extends SimpleCommand {

        private final StandardActions command;

        public EditAction(StandardActions command) { this.command = command; }

        @Override
        public void execute() {
            if (editArea != null) {
                switch (command) {
                    case COPY:
                        editArea.copy();
                        break;
                    case CUT:
                        editArea.cut();
                        break;
                    case PASTE:
                        editArea.paste();
                        break;
                    case SELECT_ALL:
                        editArea.selectAll();
                        break;
                }
                editArea.requestFocus();
            }
        }
    }

    public PreviewTabView(JabRefPreferences preferences) {
        this.preferences = preferences;
        contextMenu = new ContextMenu();
        ViewLoader.view(this)
                  .root(this)
                  .load();
    }

    public void initialize() {
        viewModel = new PreviewTabViewModel(dialogService, preferences, taskExecutor);

        lastKeyPressTime = System.currentTimeMillis();

        ActionFactory factory = new ActionFactory(Globals.getKeyPrefs());
        contextMenu.getItems().addAll(
                factory.createMenuItem(StandardActions.CUT, new PreviewTabView.EditAction(StandardActions.CUT)),
                factory.createMenuItem(StandardActions.COPY, new PreviewTabView.EditAction(StandardActions.COPY)),
                factory.createMenuItem(StandardActions.PASTE, new PreviewTabView.EditAction(StandardActions.PASTE)),
                factory.createMenuItem(StandardActions.SELECT_ALL, new PreviewTabView.EditAction(StandardActions.SELECT_ALL))
        );
        contextMenu.getStyleClass().add("context-menu");

        availableListView.itemsProperty().bind(viewModel.availableListProperty());
        viewModel.availableSelectionModelProperty().setValue(availableListView.getSelectionModel());
        new ViewModelListCellFactory<PreviewLayout>()
                .withText(PreviewLayout::getName)
                .setOnDragOver(this::dragOverAvailable)
                .install(availableListView);
        availableListView.setOnDragDetected(event -> dragDetected(availableListView, event));
        availableListView.setOnDragDropped(event -> dragDropped(availableListView, event));
        availableListView.setOnKeyTyped(event -> jumpToSearchKey(availableListView, event));
        availableListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        chosenListView.itemsProperty().bind(viewModel.chosenListProperty());
        viewModel.chosenSelectionModelProperty().setValue(chosenListView.getSelectionModel());
        new ViewModelListCellFactory<PreviewLayout>()
                .withText(PreviewLayout::getName)
                .install(chosenListView);
        chosenListView.setOnDragOver(this::dragOverChosen);
        chosenListView.setOnDragDetected(event -> dragDetected(chosenListView, event));
        chosenListView.setOnDragDropped(event -> dragDropped(chosenListView, event));
        chosenListView.setOnKeyTyped(event -> jumpToSearchKey(chosenListView, event));
        chosenListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        toRightButton.disableProperty().bind(viewModel.availableSelectionModelProperty().getValue().selectedItemProperty().isNull());

        toLeftButton.disableProperty().bind(viewModel.chosenSelectionModelProperty().getValue().selectedItemProperty().isNull());
        sortUpButton.disableProperty().bind(viewModel.chosenSelectionModelProperty().getValue().selectedItemProperty().isNull());
        sortDownButton.disableProperty().bind(viewModel.chosenSelectionModelProperty().getValue().selectedItemProperty().isNull());

        previewPane.setContent(new PreviewViewer(new BibDatabaseContext(), dialogService, Globals.stateManager));
        // previewPane.setMaxWidth(650); // FixMe: PreviewViewer is too large
        // previewPane.getContent().maxWidth(650);
        ((PreviewViewer) previewPane.getContent()).setEntry(TestEntry.getTestEntry());
        ((PreviewViewer) previewPane.getContent()).setLayout(viewModel.getCurrentLayout());
        previewPane.visibleProperty().bind(viewModel.chosenSelectionModelProperty().getValue().selectedItemProperty().isNotNull());

        editArea.clear();
        editArea.setParagraphGraphicFactory(LineNumberFactory.get(editArea));
        editArea.textProperty().addListener((observable, oldText, newText) -> editArea.setStyleSpans(0, viewModel.computeHighlighting(newText)));
        editArea.setContextMenu(contextMenu);
        editArea.visibleProperty().bind(viewModel.chosenSelectionModelProperty().getValue().selectedItemProperty().isNotNull());

        BindingsHelper.bindBidirectional(
                editArea.textProperty(),
                viewModel.chosenSelectionModelProperty().getValue().selectedItemProperty(),
                selectedItem -> update(),
                text -> {
                    PreviewLayout selectedItem = viewModel.chosenSelectionModelProperty().getValue().getSelectedItem();
                    if (selectedItem instanceof TextBasedPreviewLayout) {
                        ((TextBasedPreviewLayout) selectedItem).setText(editArea.getText().replace("\n", "__NEWLINE__"));
                    }
                    update();
                }
        );

       // ToDo: Implement selectedIsEditableProperty-Logic in ViewModel
        readOnlyLabel.visibleProperty().bind(viewModel.selectedIsEditableProperty().not());
        resetDefaultButton.disableProperty().bind(viewModel.selectedIsEditableProperty().not());
        contextMenu.getItems().get(0).disableProperty().bind(viewModel.selectedIsEditableProperty().not());
        contextMenu.getItems().get(2).disableProperty().bind(viewModel.selectedIsEditableProperty().not());

        // editArea.editableProperty().bind(true); // FixMe: Cursor caret disappears

        update();
    }

    private void update() {
        PreviewLayout selectedLayout = viewModel.chosenSelectionModelProperty().getValue().getSelectedItem();
        if (selectedLayout != null) {
            String previewText;
            try {
                ((PreviewViewer) previewPane.getContent()).setLayout(selectedLayout);
            } catch (StringIndexOutOfBoundsException exception) {
                LOGGER.warn("Parsing error.", exception);
                dialogService.showErrorDialogAndWait(Localization.lang("Parsing error"), Localization.lang("Parsing error") + ": " + Localization.lang("illegal backslash expression"), exception);
            }

            if (selectedLayout instanceof TextBasedPreviewLayout) {
                previewText = ((TextBasedPreviewLayout) selectedLayout).getText().replace("__NEWLINE__", "\n");
            } else {
                previewText = ((CitationStylePreviewLayout) selectedLayout).getSource();
            }
            editArea.replaceText(previewText);
        }
    }

    public void jumpToSearchKey(ListView<PreviewLayout> list, KeyEvent keypressed) {
        if (keypressed.getCharacter() == null) {
            return;
        }

        if (System.currentTimeMillis() - lastKeyPressTime < 1000) {
            listSearchTerm += keypressed.getCharacter().toLowerCase();
        } else {
            listSearchTerm = keypressed.getCharacter().toLowerCase();
        }

        lastKeyPressTime = System.currentTimeMillis();

        list.getItems().stream().filter(item -> item.getName().toLowerCase().startsWith(listSearchTerm))
                .findFirst().ifPresent(list::scrollTo);
    }

    public void dragOverAvailable(@SuppressWarnings("unused") PreviewLayout layout, DragEvent event) {
        if ((event.getGestureSource() != layout) && event.getDragboard().hasContent(DragAndDropDataFormats.PREVIEWLAYOUT)) {
            event.acceptTransferModes(TransferMode.MOVE);
        }
    }

    public void dragOverChosen(DragEvent event) {
        if (event.getDragboard().hasContent(DragAndDropDataFormats.PREVIEWLAYOUT)) {
            event.acceptTransferModes(TransferMode.MOVE);
        }
    }

    public void dragDetected(ListView<PreviewLayout> sourceListView, MouseEvent event) {
        PreviewLayout layout = sourceListView.getSelectionModel().getSelectedItem();
        if (layout != null) {
            ClipboardContent content = new ClipboardContent();
            Dragboard dragboard = sourceListView.startDragAndDrop(TransferMode.MOVE);
            content.put(DragAndDropDataFormats.PREVIEWLAYOUT, layout.getName());
            dragboard.setContent(content);
            dragSourceList = sourceListView;
        }
        event.consume();
    }

    public void dragDropped(ListView<PreviewLayout> targetListView, DragEvent event) {
        Dragboard dragboard = event.getDragboard();
        boolean success = false;

        if (dragboard.hasContent(DragAndDropDataFormats.PREVIEWLAYOUT)) {
            PreviewLayout draggedLayout = viewModel.findLayoutByNameOrNull((String) dragboard.getContent(DragAndDropDataFormats.PREVIEWLAYOUT));
            if (draggedLayout != null) {
                if (dragSourceList != null) {
                    dragSourceList.getItems().remove(draggedLayout);
                }

                targetListView.getItems().add(draggedLayout);

                success = true;

                if (targetListView == availableListView) {
                    targetListView.getItems().sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                }
            }
        }

        event.setDropCompleted(success);
        event.consume();
    }

    @Override
    public Node getBuilder() {
        return this;
    }

    @Override
    public void setValues() {
        // Done by bindings
    }

    @Override
    public void storeSettings() {
        viewModel.storeSettings();
    }

    @Override
    public boolean validateSettings() {
        return viewModel.validateSettings();
    }

    @Override
    public String getTabName() {
        return Localization.lang("Entry preview");
    }

    public void toRightButtonAction() { viewModel.addToChosen(); }

    public void toLeftButtonAction() { viewModel.removeFromChosen(); }

    public void sortUpButtonAction() { // FixMe: previewPane loads first in chosenList if Preview is moved
        List<Integer> newIndices = viewModel.selectedInChosenUp(chosenListView.getSelectionModel().getSelectedIndices());
        for (int index : newIndices) {
            chosenListView.getSelectionModel().select(index);
        }
        update();
    }

    public void sortDownButtonAction() {
        List<Integer> newIndices = viewModel.selectedInChosenDown(chosenListView.getSelectionModel().getSelectedIndices());
        for (int index : newIndices) {
            chosenListView.getSelectionModel().select(index);
        }
        update();
    }

    public void resetDefaultButtonAction() {
        viewModel.resetDefaultStyle();
        update();
    }
}
