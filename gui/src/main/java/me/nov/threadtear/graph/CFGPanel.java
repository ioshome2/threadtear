package me.nov.threadtear.graph;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.*;

import com.github.weisj.darklaf.components.OverlayScrollPane;
import com.github.weisj.darklaf.components.border.DarkBorders;
import me.nov.threadtear.swing.button.ReloadButton;
import me.nov.threadtear.swing.component.AutoCompletion;
import org.objectweb.asm.tree.*;

import com.mxgraph.layout.mxCompactTreeLayout;
import com.mxgraph.model.mxCell;
import com.mxgraph.util.mxCellRenderer;

import me.nov.threadtear.graph.CFGraph.CFGComponent;
import me.nov.threadtear.graph.layout.PatchedHierarchicalLayout;
import me.nov.threadtear.graph.vertex.BlockVertex;
import me.nov.threadtear.util.Images;
import me.nov.threadtear.util.format.Strings;

public class CFGPanel extends JPanel {
  private static final long serialVersionUID = 1L;
  private MethodNode mn;
  private ArrayList<Block> blocks = new ArrayList<>();
  private CFGraph graph;
  private CFGComponent graphComponent;
  private JScrollPane scrollPane;
  public boolean useTreeLayout = false;

  public CFGPanel(ClassNode cn) {
    this.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
    this.setLayout(new BorderLayout(4, 4));
    this.graph = new CFGraph();
    JPanel leftActionPanel = new JPanel();
    leftActionPanel.setLayout(new GridBagLayout());
    JPanel rightActionPanel = new JPanel();
    rightActionPanel.setLayout(new GridBagLayout());

    leftActionPanel.add(new JLabel("Control flow graph"));
    JComboBox<Object> methodSelection = new JComboBox<>(cn.methods.stream().map(m -> m.name + m.desc).toArray());
    AutoCompletion.enable(methodSelection);
    methodSelection.setPreferredSize(new Dimension(Math.min(400, methodSelection.getPreferredSize().width),
        methodSelection.getPreferredSize().height));
    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(0, 4, 0, 0);
    leftActionPanel.add(methodSelection, gbc);
    methodSelection.addActionListener(l -> {
      String item = (String) methodSelection.getSelectedItem();
      mn = cn.methods.stream().filter(m -> (m.name + m.desc).equals(item)).findAny().get();
      clear();
      generateGraph();
      SwingUtilities.invokeLater(() -> {
        Rectangle bounds = scrollPane.getViewport().getViewRect();
        JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
        horizontal.setValue((horizontal.getMaximum() - bounds.width) / 2);
      });
    });

    JPanel rightActions = new JPanel();
    rightActions.setLayout(new GridBagLayout());
    JComboBox<String> layout = new JComboBox<>(new String[] { "Hierarchial " + "layout", "Compact layout" });
    layout.addActionListener(a -> {
      useTreeLayout = layout.getSelectedIndex() == 1;
      generateGraph();
    });
    rightActions.add(layout);
    JButton save = new JButton("Save as image");
    save.addActionListener(l -> {
      File parentDir = FileSystemView.getFileSystemView().getHomeDirectory();
      JFileChooser jfc = new JFileChooser(parentDir);
      jfc.setAcceptAllFileFilterUsed(false);
      jfc.setFileFilter(new FileNameExtensionFilter("Portable Network Graphics (.png)", "png"));
      jfc.addChoosableFileFilter(new FileNameExtensionFilter("Bitmap image file (.bmp)", "bmp"));
      if (mn.name.length() < 32) {
        jfc.setSelectedFile(new File(parentDir, mn.name.replaceAll("[^a-zA-Z0-9-_.]", "_") + ".png"));
      } else {
        jfc.setSelectedFile(new File(parentDir, "method-" + (mn.name + mn.desc).hashCode() + ".png"));
      }
      int result = jfc.showSaveDialog(CFGPanel.this);
      if (result == JFileChooser.APPROVE_OPTION) {
        File output = jfc.getSelectedFile();
        String type = ((FileNameExtensionFilter) jfc.getFileFilter()).getExtensions()[0];
        BufferedImage image = mxCellRenderer.createBufferedImage(graph, null, 2, null, true, null);
        try {
          ImageIO.write(Images.watermark(image), type, output);
        } catch (IOException ioex) {
          ioex.printStackTrace();
        }
      }
    });
    rightActions.add(save);
    JButton reload = new ReloadButton();
    reload.addActionListener(l -> generateGraph());
    rightActions.add(reload);
    rightActionPanel.add(rightActions);
    JPanel topPanel = new JPanel();
    topPanel.setBorder(new EmptyBorder(1, 5, 0, 1));
    topPanel.setLayout(new BorderLayout());
    topPanel.add(leftActionPanel, BorderLayout.WEST);
    topPanel.add(rightActionPanel, BorderLayout.EAST);
    this.add(topPanel, BorderLayout.NORTH);
    graphComponent = graph.getComponent();
    graphComponent.scp = scrollPane;
    JPanel inner = new JPanel();
    inner.setBorder(new EmptyBorder(30, 30, 30, 30));
    inner.setLayout(new BorderLayout(0, 0));
    inner.add(graphComponent, BorderLayout.CENTER);
    OverlayScrollPane overlayScrollPane = new OverlayScrollPane(inner);
    scrollPane = overlayScrollPane.getScrollPane();
    scrollPane.getVerticalScrollBar().setUnitIncrement(16);
    scrollPane.setBorder(DarkBorders.createLineBorder(1, 1, 1, 1));
    this.add(overlayScrollPane, BorderLayout.CENTER);
    SwingUtilities.invokeLater(() -> {
      if (mn == null && !cn.methods.isEmpty()) {
        mn = cn.methods.get(0);
        this.generateGraph();
      }
    });
  }

  @Override
  public void updateUI() {
    super.updateUI();
    if (blocks != null) {
      generateGraph();
    }
  }

  public void generateGraph() {
    blocks.clear();
    if (mn.instructions.size() == 0) {
      this.clear();
      return;
    }
    graphComponent.scp = scrollPane;
    Converter c = new Converter(mn);
    try {
      blocks.addAll(c.convert(true, true, true, 2));
    } catch (Exception e) {
      e.printStackTrace();
      this.clear();
      return;
    }
    Object parent = graph.getDefaultParent();
    graph.getModel().beginUpdate();
    try {
      graph.removeCells(graph.getChildCells(graph.getDefaultParent(), true, true));
      existing.clear();
      if (!blocks.isEmpty()) {
        boolean first = true;
        for (Block b : blocks) {
          if (b.getInput().isEmpty() || first) {
            addBlock((mxCell) parent, b, null);
            first = false;
          }
        }
      }
      graph.getView().setScale(1);
      if (useTreeLayout) {
        mxCompactTreeLayout layout = new mxCompactTreeLayout(graph);
        layout.setResetEdges(true);
        layout.setEdgeRouting(true);
        layout.setHorizontal(false);
        layout.setMoveTree(true);
        layout.setUseBoundingBox(true);
        layout.execute(graph.getDefaultParent());
      } else {
        PatchedHierarchicalLayout layout = new PatchedHierarchicalLayout(graph);
        layout.setFineTuning(true);
        layout.setIntraCellSpacing(20d);
        layout.setInterRankCellSpacing(50d);
        layout.setDisableEdgeStyle(true);
        layout.setParallelEdgeSpacing(100d);
        layout.setUseBoundingBox(true);
        layout.execute(graph.getDefaultParent());
      }
    } finally {
      graph.getModel().endUpdate();
    }
    this.revalidate();
    this.repaint();
  }

  private Map<Block, mxCell> existing = new HashMap<>();

  private mxCell addBlock(mxCell parent, Block b, BlockVertex input) {
    mxCell v1 = null;
    if (existing.containsKey(b)) {
      mxCell cached = existing.get(b);
      if (input != null) {
        ((BlockVertex) cached.getValue()).addInput(input);
      }
      return cached;
    }
    BlockVertex vertex = new BlockVertex(mn, b, b.getNodes(), b.getLabel(),
        mn.instructions.indexOf(b.getNodes().get(0)));
    if (input != null) {
      vertex.addInput(input);
    }
    v1 = (mxCell) graph.insertVertex(parent, null, vertex, 150, 10, 80, 40,
        String.format("fillColor=%s;fontColor=%s;" + "strokeColor=%s", Strings.hexColor(getBackground().brighter()),
            Strings.hexColor(getForeground().brighter()), Strings.hexColor(getBackground().brighter().brighter())));
    graph.updateCellSize(v1); // resize cell
    existing.put(b, v1);
    if (v1 == null) {
      throw new RuntimeException();
    }
    List<Block> next = b.getOutput();
    for (int i = 0; i < next.size(); i++) {
      Block out = next.get(i);
      if (out.equals(b)) {
        graph.insertEdge(parent, null, "Infinite loop", v1, v1,
            "strokeColor=" + getEdgeColor(b, i) + ";fontColor=" + Strings.hexColor(getForeground().brighter()));
      } else {
        mxCell vertexOut = addBlock(parent, out, vertex);
        graph.insertEdge(parent, null, null, v1, vertexOut, "strokeColor=" + getEdgeColor(b, i) + ";");
      }
    }
    return v1;
  }

  private String getEdgeColor(Block b, int i) {
    if (b.endsWithJump()) {
      if (b.getOutput().size() > 1) {
        if (i == 0) {
          return "#009432";
        }
        return "#EA2027";
      }
      return "#FFC312";
    }
    if (b.endsWithSwitch()) {
      if (i == 0) {
        return "#12CBC4";
      }
      return "#9980FA";
    }
    return Strings.hexColor(getForeground().darker());
  }

  public void clear() {
    graph.getModel().beginUpdate();
    graph.removeCells(graph.getChildCells(graph.getDefaultParent(), true, true));
    graph.getModel().endUpdate();
  }
}
