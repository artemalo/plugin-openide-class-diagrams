package ru.student.diagram.ide;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.components.StoragePathMacros;
import com.intellij.util.ThreeState;
import ru.student.diagram.ui.DiagramViewState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@State(name = "ru.student.diagram.ide.DiagramStateStore",
        storages = @Storage(value = StoragePathMacros.WORKSPACE_FILE, useSaveThreshold = ThreeState.NO))
public final class DiagramStateStore implements PersistentStateComponent<DiagramStateStore.StoredState> {
    public static final class StoredState {
        public List<Entry> diagrams = new ArrayList<>();
    }

    public static final class Entry {
        public String key = "";
        public DiagramViewState view = new DiagramViewState();
    }

    private StoredState state = new StoredState();

    @Override public synchronized StoredState getState() { return state; }

    @Override public synchronized void loadState(StoredState value) {
        state = value == null ? new StoredState() : value;
    }

    public synchronized DiagramViewState find(String key) {
        if (state.diagrams == null) return null;
        for (Entry entry : state.diagrams)
            if (entry != null && key.equals(entry.key)) return entry.view;
        return null;
    }

    public synchronized void save(String key, DiagramViewState view) {
        StoredState replacement = new StoredState();
        if (state.diagrams != null) for (Entry entry : state.diagrams)
            if (entry != null && !key.equals(entry.key)) replacement.diagrams.add(entry);
        Entry entry = new Entry();
        entry.key = key;
        entry.view = view;
        replacement.diagrams.add(entry);
        state = replacement;
    }

    public static String keyFor(PsiDiagramBuilder.Selection selection) {
        List<String> urls = selection.urls().stream().distinct().sorted(Comparator.naturalOrder()).toList();
        StringBuilder key = new StringBuilder();
        appendPart(key, selection.onlyClass() == null ? "" : selection.onlyClass());
        for (String url : urls) appendPart(key, url);
        return key.toString();
    }

    private static void appendPart(StringBuilder key, String value) {
        key.append(value.length()).append(':').append(value);
    }
}
