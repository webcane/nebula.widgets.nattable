/*******************************************************************************
 * Copyright (c) 2012 Original authors and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 *     Original authors and others - initial API and implementation
 ******************************************************************************/
package org.eclipse.nebula.widgets.nattable.style.editor.command;

import static org.eclipse.nebula.widgets.nattable.config.CellConfigAttributes.CELL_STYLE;
import static org.eclipse.nebula.widgets.nattable.style.DisplayMode.NORMAL;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.eclipse.nebula.widgets.nattable.command.AbstractLayerCommandHandler;
import org.eclipse.nebula.widgets.nattable.config.IConfigRegistry;
import org.eclipse.nebula.widgets.nattable.layer.LabelStack;
import org.eclipse.nebula.widgets.nattable.layer.cell.ColumnOverrideLabelAccumulator;
import org.eclipse.nebula.widgets.nattable.layer.event.ColumnVisualUpdateEvent;
import org.eclipse.nebula.widgets.nattable.layer.event.VisualRefreshEvent;
import org.eclipse.nebula.widgets.nattable.persistence.IPersistable;
import org.eclipse.nebula.widgets.nattable.persistence.StylePersistor;
import org.eclipse.nebula.widgets.nattable.selection.SelectionLayer;
import org.eclipse.nebula.widgets.nattable.style.CellStyleAttributes;
import org.eclipse.nebula.widgets.nattable.style.Style;
import org.eclipse.nebula.widgets.nattable.style.editor.ColumnStyleEditorDialog;
import org.eclipse.swt.widgets.Display;

/**
 * 
 * 1. Captures a new style using the <code>StyleEditorDialog</code> 
 * 2. Registers style from step 1 in the <code>ConfigRegistry</code> with a new label 
 * 3. Applies the label from step 2 to all cells in the selected column
 * 
 */
public class DisplayColumnStyleEditorCommandHandler extends AbstractLayerCommandHandler<DisplayColumnStyleEditorCommand> implements IPersistable {
	
	protected static final String PERSISTENCE_PREFIX = "userDefinedColumnStyle"; //$NON-NLS-1$
	protected static final String USER_EDITED_STYLE_LABEL = "USER_EDITED_STYLE"; //$NON-NLS-1$
	protected static final String USER_EDITED_COLUMN_STYLE_LABEL_PREFIX = "USER_EDITED_STYLE_FOR_INDEX_"; //$NON-NLS-1$

	protected final SelectionLayer selectionLayer;
	protected ColumnOverrideLabelAccumulator columnLabelAccumulator;
	private final IConfigRegistry configRegistry;
	protected ColumnStyleEditorDialog dialog;
	protected final Map<String, Style> stylesToPersist = new HashMap<String, Style>();

	public DisplayColumnStyleEditorCommandHandler(SelectionLayer selectionLayer, ColumnOverrideLabelAccumulator labelAccumulator, IConfigRegistry configRegistry) {
		this.selectionLayer = selectionLayer;
		this.columnLabelAccumulator = labelAccumulator;
		this.configRegistry = configRegistry;
	}

	@Override
	public boolean doCommand(DisplayColumnStyleEditorCommand command) {
		int columnIndexOfClick = command.getNattableLayer().getColumnIndexByPosition(command.columnPosition);
		
		LabelStack configLabels = new LabelStack();
		columnLabelAccumulator.accumulateConfigLabels(configLabels, columnIndexOfClick, 0);
		configLabels.addLabel(getConfigLabel(columnIndexOfClick));
		
		// Column style
		Style clickedCellStyle = (Style) configRegistry.getConfigAttribute(CELL_STYLE, NORMAL, configLabels.getLabels());
		
		dialog = new ColumnStyleEditorDialog(Display.getCurrent().getActiveShell(), clickedCellStyle);
		dialog.open();

		if(dialog.isCancelPressed()) {
			return true;
		}
		
		int[] selectedColumns = getSelectedColumnIndeces();
		if (selectedColumns.length > 0) {
			applySelectedStyleToColumns(command, selectedColumns);
			//fire refresh event
			this.selectionLayer.fireLayerEvent(new ColumnVisualUpdateEvent(selectionLayer, selectionLayer.getSelectedColumnPositions()));
		}
		else {
			applySelectedStyle();
			//fire refresh event
			this.selectionLayer.fireLayerEvent(new VisualRefreshEvent(selectionLayer));
		}
		
		return true;
	}

	private int[] getSelectedColumnIndeces() {
		int[] selectedColumnPositions = selectionLayer.getFullySelectedColumnPositions();
		int[] selectedColumnIndeces = new int[selectedColumnPositions.length];
		for (int i=0; i<selectedColumnPositions.length; i++) {
			selectedColumnIndeces[i] = selectionLayer.getColumnIndexByPosition(selectedColumnPositions[i]);
		}
		return selectedColumnIndeces;
	}

	@Override
	public Class<DisplayColumnStyleEditorCommand> getCommandClass() {
		return DisplayColumnStyleEditorCommand.class;
	}

	protected void applySelectedStyleToColumns(DisplayColumnStyleEditorCommand command, int[] columnIndeces) {
		// Read the edited styles
		Style newColumnCellStyle = dialog.getNewColumnCellStyle(); 

		for (int i=0; i<columnIndeces.length; i++) {
			final int columnIndex = columnIndeces[i];
			
			String configLabel = getConfigLabel(columnIndex);
			applySelectedStyle(newColumnCellStyle, configLabel);
			
			if (newColumnCellStyle != null) {
				columnLabelAccumulator.registerColumnOverridesOnTop(columnIndex, configLabel);
			}
			else {
				columnLabelAccumulator.unregisterOverrides(columnIndex, configLabel);
			}
		}
	}

	protected void applySelectedStyle() {
		// Read the edited styles
		Style newColumnCellStyle = dialog.getNewColumnCellStyle(); 

		applySelectedStyle(newColumnCellStyle, USER_EDITED_STYLE_LABEL);
		
		if (newColumnCellStyle != null) {
			columnLabelAccumulator.registerOverridesOnTop(USER_EDITED_STYLE_LABEL);
		}
		else {
			columnLabelAccumulator.unregisterOverrides(USER_EDITED_STYLE_LABEL);
		}
	}

	protected void applySelectedStyle(Style newColumnCellStyle, String configLabel) {
		if (newColumnCellStyle == null) {
			stylesToPersist.remove(configLabel);
		} else {
			newColumnCellStyle.setAttributeValue(CellStyleAttributes.BORDER_STYLE, dialog.getNewColumnBorderStyle());
			stylesToPersist.put(configLabel, newColumnCellStyle);
		}
		configRegistry.registerConfigAttribute(CELL_STYLE, newColumnCellStyle, NORMAL, configLabel);
	}
	
	protected String getConfigLabel(int columnIndex) {
		return USER_EDITED_COLUMN_STYLE_LABEL_PREFIX + columnIndex;
	}

	@Override
	public void loadState(String prefix, Properties properties) {
		prefix = prefix + DOT + PERSISTENCE_PREFIX;
		Set<Object> keySet = properties.keySet();

		for (Object key : keySet) {
			String keyString = (String) key;

			// Relevant Key
			if (keyString.contains(PERSISTENCE_PREFIX)) {
				if (keyString.contains(USER_EDITED_COLUMN_STYLE_LABEL_PREFIX)) {
					int colIndex = parseColumnIndexFromKey(keyString);
					
					// Has the config label been processed
					String configLabel = getConfigLabel(colIndex);
					if (!stylesToPersist.keySet().contains(configLabel)) {
						Style savedStyle = StylePersistor.loadStyle(prefix + DOT + configLabel, properties);
						
						configRegistry.registerConfigAttribute(CELL_STYLE, savedStyle, NORMAL, configLabel);
						stylesToPersist.put(configLabel, savedStyle);
						columnLabelAccumulator.registerColumnOverrides(colIndex, configLabel);
					}
				}
				else {
					// Has the config label been processed
					if (!stylesToPersist.keySet().contains(USER_EDITED_STYLE_LABEL)) {
						Style savedStyle = StylePersistor.loadStyle(prefix + DOT + USER_EDITED_STYLE_LABEL, properties);
						
						configRegistry.registerConfigAttribute(CELL_STYLE, savedStyle, NORMAL, USER_EDITED_STYLE_LABEL);
						stylesToPersist.put(USER_EDITED_STYLE_LABEL, savedStyle);
						columnLabelAccumulator.registerOverrides(USER_EDITED_STYLE_LABEL, USER_EDITED_STYLE_LABEL);
					}
				}
			}
		}
	}
	
	protected int parseColumnIndexFromKey(String keyString) {
		int colLabelStartIndex = keyString.indexOf(USER_EDITED_COLUMN_STYLE_LABEL_PREFIX);
		String columnConfigLabel = keyString.substring(colLabelStartIndex, keyString.indexOf('.', colLabelStartIndex));
		int lastUnderscoreInLabel = columnConfigLabel.lastIndexOf('_', colLabelStartIndex);

		return Integer.parseInt(columnConfigLabel.substring(lastUnderscoreInLabel + 1));
	}

	@Override
	public void saveState(String prefix, Properties properties) {
		prefix = prefix + DOT + PERSISTENCE_PREFIX;

		for (Map.Entry<String, Style> labelToStyle : stylesToPersist.entrySet()) {
			Style style = labelToStyle.getValue();
			String label = labelToStyle.getKey();

			StylePersistor.saveStyle(prefix + DOT + label, properties, style);
		}
	}
}
