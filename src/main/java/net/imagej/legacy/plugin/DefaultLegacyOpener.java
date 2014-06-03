/*
 * #%L
 * ImageJ software for multidimensional image processing and analysis.
 * %%
 * Copyright (C) 2009 - 2014 Board of Regents of the University of
 * Wisconsin-Madison, Broad Institute of MIT and Harvard, and Max Planck
 * Institute of Molecular Cell Biology and Genetics.
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */

package net.imagej.legacy.plugin;

import ij.ImagePlus;
import io.scif.Metadata;
import io.scif.app.SCIFIOApp;
import io.scif.img.SCIFIOImgPlus;

import java.io.IOException;
import java.util.concurrent.Future;

import net.imagej.Dataset;
import net.imagej.display.ImageDisplay;
import net.imagej.legacy.DefaultLegacyService;
import net.imagej.legacy.IJ1Helper;
import net.imagej.legacy.ImageJ2Options;
import net.imagej.legacy.translate.DefaultImageTranslator;
import net.imagej.legacy.translate.ImageTranslator;

import org.scijava.Cancelable;
import org.scijava.Context;
import org.scijava.Priority;
import org.scijava.app.App;
import org.scijava.app.AppService;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.display.DisplayService;
import org.scijava.io.IOPlugin;
import org.scijava.io.IOService;
import org.scijava.log.LogService;
import org.scijava.module.Module;
import org.scijava.module.ModuleService;
import org.scijava.options.OptionsService;
import org.scijava.plugin.Plugin;
import org.scijava.service.Service;

/**
 * The default {@link LegacyOpener} plugin.
 * <p>
 * When the {@link net.imagej.legacy.DefaultLegacyHooks} are installed, the
 * {@link LegacyOpener} plugin (if any) is given a chance to handle various
 * {@code open} requests from ImageJ 1.x by using IJ2.
 * </p>
 * 
 * @author Mark Hiner
 */
@Plugin(type = LegacyOpener.class, priority = Priority.LOW_PRIORITY)
public class DefaultLegacyOpener implements LegacyOpener {

	private DefaultLegacyService legacyService;
	private DisplayService displayService;
	private ModuleService moduleService;
	private CommandService commandService;
	private OptionsService optionsService;
	private AppService appService;
	private IOService ioService;
	private LogService logService;

	@Override
	public Object open(String path, final int planeIndex,
		final boolean displayResult)
	{
		final Context c = IJ1Helper.getLegacyContext();
		ImagePlus imp = null;

		legacyService = getCached(legacyService, DefaultLegacyService.class, c);
		displayService = getCached(displayService, DisplayService.class, c);
		moduleService = getCached(moduleService, ModuleService.class, c);
		commandService = getCached(commandService, CommandService.class, c);
		optionsService = getCached(optionsService, OptionsService.class, c);
		appService = getCached(appService, AppService.class, c);
		ioService = getCached(ioService, IOService.class, c);
		logService = getCached(logService, LogService.class, c);

		// Check to see if SCIFIO has been disabled
		Boolean useSCIFIO = optionsService.getOptions(ImageJ2Options.class).isUseSCIFIO();
		if (useSCIFIO == null || !useSCIFIO) return null;

		if (path == null) {
			final CommandInfo command = commandService.getCommand(GetPath.class);
			final String[] selectedPath = new String[1];
			final Future<Module> result =
				moduleService.run(command, true, "path", selectedPath);
			final Module module = moduleService.waitFor(result);
			// Check if the module failed
			if (module == null) return null;
			// Check if the module was canceled
			if (Cancelable.class.isAssignableFrom(module.getClass())) {
				if (((Cancelable)module).isCanceled()) {
					return Boolean.TRUE;
				}
			}

			path = selectedPath[0];
		}

		Object data = null;
		try {
			final IOPlugin<?> opener = ioService.getOpener(path);
			if (opener == null) {
				logService.warn("No appropriate format found: " + path);
				return null;
			}
			data = opener.open(path);
			if (data == null) {
				logService.warn("Opening was canceled.");
				return null;
			}
		}
		catch (final IOException exc) {
			logService.error(exc);
		}

		if (data != null) {
			if (data instanceof Dataset) {
				final Dataset d = (Dataset) data;

				if (displayResult) {
					final ImageDisplay imageDisplay =
						(ImageDisplay) displayService.createDisplay(d);

					imp = legacyService.getImageMap().lookupImagePlus(imageDisplay);

					legacyService.getIJ1Helper().updateRecentMenu(
						((Dataset) data).getImgPlus().getSource());
				}
				else {
					// Manually register the dataset, without creating a display
					final ImageTranslator it = new DefaultImageTranslator(legacyService);
					imp = it.createLegacyImage(d);
				}
				// Set information about how this dataset was opened.
				String loadingInfo = "";
				App app = appService.getApp(SCIFIOApp.NAME);
				// Get the SCIFIO version
				if (app != null) {
					loadingInfo +=
						"Opened with SCIFIO version: " + app.getVersion() + "\n";
				}
				// Get the SCIFIO format
				if (((Dataset) data).getImgPlus() instanceof SCIFIOImgPlus) {
					final SCIFIOImgPlus<?> scifioImp =
						(SCIFIOImgPlus<?>) ((Dataset) data).getImgPlus();
					final Metadata metadata = scifioImp.getMetadata();
					if (metadata != null) {
						loadingInfo +=
							"Used format plugin: " + metadata.getFormatName() + "\n";
					}
				}

				if (imp != null) {
					imp.setProperty("Info", loadingInfo);
					return imp;
				}
			}
			return data;
		}
		return null;
	}

	// -- Helper methods --

	private <T extends Service> T getCached(T service, Class<T> serviceClass, Context ctx) {
		if (service != null) return service;
		return ctx.getService(serviceClass);
	}
}
