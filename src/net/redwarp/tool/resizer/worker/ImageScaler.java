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
package net.redwarp.tool.resizer.worker;

import net.redwarp.tool.resizer.misc.Configuration;
import net.redwarp.tool.resizer.misc.Localization;
import net.redwarp.tool.resizer.table.Operation;
import net.redwarp.tool.resizer.table.OperationStatus;

import java.awt.*;
import java.awt.RenderingHints.Key;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.*;

public class ImageScaler extends SwingWorker<Void, Operation> {

  private File inputFile;
  private Operation operation;
  private ScreenDensity inputDensity;
  private static ExecutorService executor = Executors
      .newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  private static final Object fileLock = new Object();
  private static final Object folderLock = new Object();

  public ImageScaler(final Operation operation,
                     final ScreenDensity inputDensity) {
    this.operation = operation;
    this.inputFile = operation.getFile();
    this.inputDensity = inputDensity;
  }

  @Override
  protected Void doInBackground() throws Exception {
    try {
      BufferedImage inputImage;
      synchronized (fileLock) {
//        inputImage = ImageIO.read(this.inputFile);
          Image tempImage = Toolkit.getDefaultToolkit().createImage(this.inputFile.getAbsolutePath());
          ImageIcon tempIcon = new ImageIcon(tempImage);
          inputImage = new BufferedImage(tempIcon.getIconWidth(), tempIcon.getIconHeight(), BufferedImage.TYPE_INT_ARGB);
          inputImage.getGraphics().drawImage(tempImage, 0, 0, null);
      }
      if (inputImage == null) {
        this.operation.setStatus(OperationStatus.ERROR,
                                 Localization.get("error_wrong_png"));
        this.publish(this.operation);
        return null;
      } else {
//                if (inputImage.getType() != BufferedImage.TYPE_INT_ARGB) {
//                    BufferedImage tempImage = new BufferedImage(
//                            inputImage.getWidth(), inputImage.getHeight(),
//                            BufferedImage.TYPE_INT_ARGB);
//                    Graphics2D g = tempImage.createGraphics();
//                    g.drawImage(inputImage, 0, 0, tempImage.getWidth(),
//                            tempImage.getHeight(), 0, 0, inputImage.getWidth(),
//                            inputImage.getHeight(), null);
//                    g.dispose();
//                    inputImage = tempImage;
//                }
      }

      this.operation.setStatus(OperationStatus.IN_PROGRESS);
      this.publish(this.operation);

      List<ScreenDensity> densityList = Configuration.getSettings()
          .getSupportedScreenDensity();

      File parent = this.inputFile.getParentFile();
      String resFolderName = parent.getName(); //
      parent = parent.getParentFile(); // 1 level-up folder
      for (ScreenDensity density : densityList) {
        if (!density.isActive()) {
          continue;
        }
        File outputFolder;

        synchronized (folderLock) {
          String tname = resFolderName.replace( "xxhdpi", density.getName());
          outputFolder = new File(parent, tname);
          if (!outputFolder.exists()) {
            outputFolder.mkdir();
          }
        }

        String name;
        Output output = null;
        int extensionPos = this.inputFile.getName().lastIndexOf('.');
        if (extensionPos != -1) {
          String
              extension =
              this.inputFile.getName().substring(extensionPos, this.inputFile.getName().length());
          if (".png".equalsIgnoreCase(extension)) {
            output = Output.PNG;
          } else if (".jpg".equalsIgnoreCase(extension) || ".jpeg".equalsIgnoreCase(extension)) {
            output = Output.JPG;
          }
        }

        if (output != null) {
          name = this.inputFile.getName().substring(0, extensionPos) + "." + output.getFormat();
        } else {
          name = this.inputFile.getName();
          output = Output.PNG;
        }

        File outputFile = new File(outputFolder, name);
        if (outputFile.exists()) {
          outputFile.delete();
        }

        BufferedImage outputImage;
        if (this.inputFile.getName().endsWith(".9.png")) {
          BufferedImage trimedImage = this.trim9PBorder(inputImage);

          float ratio = density.getScale()
                        / this.inputDensity.getScale();
          trimedImage = this.rescaleImage(trimedImage,
                                          (int) (ratio * trimedImage.getWidth()),
                                          (int) (ratio * trimedImage.getHeight()));

          BufferedImage borderImage;

          int w = trimedImage.getWidth();
          int h = trimedImage.getHeight();

          try {
            borderImage = this.generateBordersImage(inputImage, w, h);
          } catch (Wrong9PatchException e) {
            this.operation.setStatus(OperationStatus.ERROR,
                                     Localization.get("error_wrong_9p"));
            this.publish(this.operation);
            return null;
          }

          int[] rgbArray = new int[w * h];
          trimedImage.getRGB(0, 0, w, h, rgbArray, 0, w);
          borderImage.setRGB(1, 1, w, h, rgbArray, 0, w);
          rgbArray = null;

          outputImage = borderImage;
        } else {

          float ratio = density.getScale()
                        / this.inputDensity.getScale();
          outputImage = this.rescaleImage(inputImage,
                                          (int) (ratio * inputImage.getWidth()),
                                          (int) (ratio * inputImage.getHeight()));
        }

        try {

          synchronized (fileLock) {
            // Let's do the worst hack in the universe to speed things up !
            if (Configuration.getSettings().shouldKeepSameDensityFile() && output == Output.JPG
                && outputImage.getWidth() == inputImage.getWidth()
                && outputImage.getHeight() == inputImage.getHeight()) {
              ImageWriter.copy(inputFile, outputFile);
            } else {
              ImageWriter.write(outputImage, output, outputFile);
            }
          }

        } catch (IOException e) {
          this.operation.setStatus(OperationStatus.ERROR);
          this.publish(this.operation);
          return null;
        }
      }
      // }
      this.operation.setStatus(OperationStatus.FINISH);
      this.publish(this.operation);
    } catch (Exception e) {
      this.operation.setStatus(OperationStatus.ERROR);
      this.publish(this.operation);
    }

    return null;
  }

  public void post() {
    executor.submit(this);
  }

  private BufferedImage rescaleImage(BufferedImage image, int targetWidth,
                                     int targetHeight) {
    if (targetWidth == 0) {
      targetWidth = 1;
    }
    if (targetHeight == 0) {
      targetHeight = 1;
    }
    if (targetWidth * 2 < image.getWidth() - 1) {
      BufferedImage tempImage = this.rescaleImage(image,
                                                  image.getWidth() / 2, image.getHeight() / 2);
      return this.rescaleImage(tempImage, targetWidth, targetHeight);
    } else {
      BufferedImage outputImage = new BufferedImage(targetWidth,
                                                    targetHeight, BufferedImage.TYPE_INT_ARGB);
      Graphics2D graphics = outputImage.createGraphics();
      Map<Key, Object> hints = new HashMap<RenderingHints.Key, Object>();
      hints.put(RenderingHints.KEY_DITHERING,
                RenderingHints.VALUE_DITHER_ENABLE);
      hints.put(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      hints.put(RenderingHints.KEY_RENDERING,
                RenderingHints.VALUE_RENDER_QUALITY);
      hints.put(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON);
      hints.put(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
      graphics.setRenderingHints(hints);

      graphics.drawImage(image, 0, 0, outputImage.getWidth(),
                         outputImage.getHeight(), null);
      graphics.dispose();

      return outputImage;
    }
  }

  private BufferedImage trim9PBorder(BufferedImage inputImage) {
    BufferedImage trimedImage = new BufferedImage(
        inputImage.getWidth() - 2, inputImage.getHeight() - 2,
        BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = trimedImage.createGraphics();
    g.drawImage(inputImage, 0, 0, trimedImage.getWidth(),
                trimedImage.getHeight(), 1, 1, inputImage.getWidth() - 1,
                inputImage.getHeight() - 1, null);
    g.dispose();
    return trimedImage;
  }

  private void enforceBorderColors(BufferedImage inputImage) {
    Graphics2D g = inputImage.createGraphics();
    g.setBackground(new Color(0, 0, 0, 0));
    g.clearRect(1, 1, inputImage.getWidth() - 2, inputImage.getHeight() - 2);
    g.dispose();
    int w = inputImage.getWidth();
    int h = inputImage.getHeight();
    int[] rgb = new int[w * h];

    inputImage.getRGB(0, 0, w, h, rgb, 0, w);

    for (int i = 0; i < rgb.length; i++) {
      if ((0xff000000 & rgb[i]) != 0) {
        rgb[i] = 0xff000000;
      }
    }
    inputImage.setRGB(0, 0, w, h, rgb, 0, w);
    inputImage.setRGB(0, 0, 0x0);
    inputImage.setRGB(0, h - 1, 0x0);
    inputImage.setRGB(w - 1, h - 1, 0x0);
    inputImage.setRGB(w - 1, 0, 0x0);
  }

  private BufferedImage generateBordersImage(BufferedImage source,
                                             int trimedWidth, int trimedHeight)
      throws Wrong9PatchException {
    BufferedImage finalBorder = new BufferedImage(trimedWidth + 2,
                                                  trimedHeight + 2, BufferedImage.TYPE_INT_ARGB);
    int cutW = source.getWidth() - 2;
    int cutH = source.getHeight() - 2;
    {
      // left border
      BufferedImage leftBorder = new BufferedImage(1, cutH,
                                                   BufferedImage.TYPE_INT_ARGB);
      leftBorder.setRGB(0, 0, 1, cutH,
                        source.getRGB(0, 1, 1, cutH, null, 0, 1), 0, 1);
      this.verifyBorderImage(leftBorder);
      leftBorder = this.resizeBorder(leftBorder, 1, trimedHeight);
      finalBorder.setRGB(0, 1, 1, trimedHeight,
                         leftBorder.getRGB(0, 0, 1, trimedHeight, null, 0, 1), 0, 1);
    }
    {
      // right border
      BufferedImage rightBorder = new BufferedImage(1, cutH,
                                                    BufferedImage.TYPE_INT_ARGB);
      rightBorder.setRGB(0, 0, 1, cutH,
                         source.getRGB(cutW + 1, 1, 1, cutH, null, 0, 1), 0, 1);
      this.verifyBorderImage(rightBorder);
      rightBorder = this.resizeBorder(rightBorder, 1, trimedHeight);
      finalBorder
          .setRGB(trimedWidth + 1, 1, 1, trimedHeight, rightBorder
              .getRGB(0, 0, 1, trimedHeight, null, 0, 1), 0, 1);
    }
    {
      // top border
      BufferedImage topBorder = new BufferedImage(cutW, 1,
                                                  BufferedImage.TYPE_INT_ARGB);
      topBorder.setRGB(0, 0, cutW, 1,
                       source.getRGB(1, 0, cutW, 1, null, 0, cutW), 0, cutW);
      this.verifyBorderImage(topBorder);
      topBorder = this.resizeBorder(topBorder, trimedWidth, 1);
      finalBorder.setRGB(1, 0, trimedWidth, 1, topBorder.getRGB(0, 0,
                                                                trimedWidth, 1, null, 0,
                                                                trimedWidth), 0, trimedWidth);
    }
    {
      // bottom border
      BufferedImage bottomBorder = new BufferedImage(cutW, 1,
                                                     BufferedImage.TYPE_INT_ARGB);
      bottomBorder
          .setRGB(0, 0, cutW, 1,
                  source.getRGB(1, cutH + 1, cutW, 1, null, 0, cutW),
                  0, cutW);
      this.verifyBorderImage(bottomBorder);
      bottomBorder = this.resizeBorder(bottomBorder, trimedWidth, 1);
      finalBorder.setRGB(1, trimedHeight + 1, trimedWidth, 1,
                         bottomBorder.getRGB(0, 0, trimedWidth, 1, null, 0,
                                             trimedWidth), 0, trimedWidth);
    }

    return finalBorder;
  }

  private BufferedImage resizeBorder(final BufferedImage border,
                                     int targetWidth, int targetHeight) {
    if (targetWidth > border.getWidth()
        || targetHeight > border.getHeight()) {
      BufferedImage endImage = this.rescaleImage(border, targetWidth,
                                                 targetHeight);
      this.enforceBorderColors(endImage);
      return endImage;
    }

    int w = border.getWidth();
    int h = border.getHeight();
    int[] data = border.getRGB(0, 0, w, h, null, 0, w);
    int[] newData = new int[targetWidth * targetHeight];

    float widthRatio = (float) Math.max(targetWidth - 1, 1)
                       / (float) Math.max(w - 1, 1);
    float heightRatio = (float) Math.max(targetHeight - 1, 1)
                        / (float) Math.max(h - 1, 1);

    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        if ((0xff000000 & data[y * w + x]) != 0) {
          int newX = Math.min(Math.round(x * widthRatio),
                              targetWidth - 1);
          int newY = Math.min(Math.round(y * heightRatio),
                              targetHeight - 1);

          newData[newY * targetWidth + newX] = data[y * w + x];
        }
      }
    }

    BufferedImage img = new BufferedImage(targetWidth, targetHeight,
                                          BufferedImage.TYPE_INT_ARGB);
    img.setRGB(0, 0, targetWidth, targetHeight, newData, 0, targetWidth);

    return img;
  }

  private void verifyBorderImage(BufferedImage border)
      throws Wrong9PatchException {
    int[] rgb = border.getRGB(0, 0, border.getWidth(), border.getHeight(),
                              null, 0, border.getWidth());
    for (int i = 0; i < rgb.length; i++) {
      if ((0xff000000 & rgb[i]) != 0) {
        if (rgb[i] != 0xff000000 && rgb[i] != 0xffff0000) {
          throw new Wrong9PatchException();
        }
      }
    }
  }
}
