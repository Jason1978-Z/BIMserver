package org.bimserver.plugins;

import java.io.Closeable;

/******************************************************************************
 * Copyright (C) 2009-2016  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see {@literal<http://www.gnu.org/licenses/>}.
 *****************************************************************************/

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.bimserver.BimserverDatabaseException;
import org.bimserver.emf.MetaDataManager;
import org.bimserver.emf.Schema;
import org.bimserver.interfaces.objects.SPluginBundle;
import org.bimserver.interfaces.objects.SPluginBundleType;
import org.bimserver.interfaces.objects.SPluginBundleVersion;
import org.bimserver.interfaces.objects.SPluginInformation;
import org.bimserver.interfaces.objects.SPluginType;
import org.bimserver.models.store.Parameter;
import org.bimserver.models.store.ServiceDescriptor;
import org.bimserver.plugins.classloaders.DelegatingClassLoader;
import org.bimserver.plugins.classloaders.EclipsePluginClassloader;
import org.bimserver.plugins.classloaders.FileJarClassLoader;
import org.bimserver.plugins.classloaders.PublicFindClassClassLoader;
import org.bimserver.plugins.deserializers.DeserializeException;
import org.bimserver.plugins.deserializers.DeserializerPlugin;
import org.bimserver.plugins.deserializers.StreamingDeserializerPlugin;
import org.bimserver.plugins.modelchecker.ModelCheckerPlugin;
import org.bimserver.plugins.modelcompare.ModelComparePlugin;
import org.bimserver.plugins.modelmerger.ModelMergerPlugin;
import org.bimserver.plugins.objectidms.ObjectIDM;
import org.bimserver.plugins.objectidms.ObjectIDMException;
import org.bimserver.plugins.objectidms.ObjectIDMPlugin;
import org.bimserver.plugins.queryengine.QueryEnginePlugin;
import org.bimserver.plugins.renderengine.RenderEnginePlugin;
import org.bimserver.plugins.serializers.MessagingSerializerPlugin;
import org.bimserver.plugins.serializers.MessagingStreamingSerializerPlugin;
import org.bimserver.plugins.serializers.SerializerPlugin;
import org.bimserver.plugins.serializers.StreamingSerializerPlugin;
import org.bimserver.plugins.services.BimServerClientInterface;
import org.bimserver.plugins.services.NewExtendedDataOnProjectHandler;
import org.bimserver.plugins.services.NewExtendedDataOnRevisionHandler;
import org.bimserver.plugins.services.NewRevisionHandler;
import org.bimserver.plugins.services.ServicePlugin;
import org.bimserver.plugins.stillimagerenderer.StillImageRenderPlugin;
import org.bimserver.plugins.web.WebModulePlugin;
import org.bimserver.shared.AuthenticationInfo;
import org.bimserver.shared.BimServerClientFactory;
import org.bimserver.shared.ChannelConnectionException;
import org.bimserver.shared.ServiceFactory;
import org.bimserver.shared.exceptions.PluginException;
import org.bimserver.shared.exceptions.ServiceException;
import org.bimserver.shared.meta.SServicesMap;
import org.bimserver.utils.PathUtils;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class PluginManager implements PluginManagerInterface {
	private static final Logger LOGGER = LoggerFactory.getLogger(PluginManager.class);
	private final Map<Class<? extends Plugin>, Set<PluginContext>> implementations = new LinkedHashMap<>();
	private final Map<Plugin, PluginContext> pluginToPluginContext = new HashMap<>();
	private final Map<PluginBundleIdentifier, PluginBundle> pluginBundleIdentifierToPluginBundle = new HashMap<>();
	private final Map<PluginBundleVersionIdentifier, PluginBundle> pluginBundleVersionIdentifierToPluginBundle = new HashMap<>();
	private final Set<PluginChangeListener> pluginChangeListeners = new HashSet<>();
	private final Path tempDir;
	private final String baseClassPath;
	private final ServiceFactory serviceFactory;
	private final NotificationsManagerInterface notificationsManagerInterface;
	private final SServicesMap servicesMap;
	private final Path pluginsDir;
	private BimServerClientFactory bimServerClientFactory;
	private MetaDataManager metaDataManager;

	public PluginManager(Path tempDir, Path pluginsDir, String baseClassPath, ServiceFactory serviceFactory, NotificationsManagerInterface notificationsManagerInterface, SServicesMap servicesMap) {
		LOGGER.debug("Creating new PluginManager");
		this.pluginsDir = pluginsDir;
		this.tempDir = tempDir;
		this.baseClassPath = baseClassPath;
		this.serviceFactory = serviceFactory;
		this.notificationsManagerInterface = notificationsManagerInterface;
		this.servicesMap = servicesMap;

		if (pluginsDir != null) {
			if (!Files.isDirectory(pluginsDir)) {
				try {
					Files.createDirectories(pluginsDir);
				} catch (IOException e) {
					e.printStackTrace();
				}
			} else {
				try {
					for (Path file : PathUtils.list(pluginsDir)) {
						try {
							PluginBundleVersionIdentifier pluginBundleVersionIdentifier = PluginBundleVersionIdentifier.fromFileName(file.getFileName().toString());
							loadPluginsFromJar(pluginBundleVersionIdentifier, file, extractPluginBundleFromJar(file), extractPluginBundleVersionFromJar(file));
							LOGGER.info("Loading " + pluginBundleVersionIdentifier.getHumanReadable());
						} catch (PluginException e) {
							LOGGER.error("", e);
						}
					}
				} catch (IOException e) {
					LOGGER.error("", e);
				}
			}
		}
	}

	public void loadPluginsFromEclipseProjectNoExceptions(Path projectRoot) {
		try {
			loadPluginsFromEclipseProject(projectRoot);
		} catch (PluginException e) {
			LOGGER.error("", e);
		}
	}

	public PluginBundle loadJavaProject(Path projectRoot, Path pomFile, Path pluginFolder, PluginDescriptor pluginDescriptor) throws PluginException, FileNotFoundException, IOException, XmlPullParserException {
		MavenXpp3Reader mavenreader = new MavenXpp3Reader();
		Model model = mavenreader.read(new FileReader(pomFile.toFile()));
		PluginBundleVersionIdentifier pluginBundleVersionIdentifier = new PluginBundleVersionIdentifier(model.getGroupId(), model.getArtifactId(), model.getVersion());
		
		if (pluginBundleIdentifierToPluginBundle.containsKey(pluginBundleVersionIdentifier.getPluginBundleIdentifier())) {
			throw new PluginException("Plugin " + pluginBundleVersionIdentifier.getPluginBundleIdentifier().getHumanReadable() + " already loaded (version " + pluginBundleIdentifierToPluginBundle.get(pluginBundleVersionIdentifier.getPluginBundleIdentifier()).getPluginBundleVersion().getVersion() + ")");
		}
		DelegatingClassLoader delegatingClassLoader = new DelegatingClassLoader(getClass().getClassLoader());
		PublicFindClassClassLoader previous = new PublicFindClassClassLoader(getClass().getClassLoader()) {
			@Override
			public Class<?> findClass(String name) throws ClassNotFoundException {
				return null;
			}

			@Override
			public URL findResource(String name) {
				return null;
			}

			@Override
			public void dumpStructure(int indent) {
			}
		};

		List<org.bimserver.plugins.Dependency> bimServerDependencies = new ArrayList<>();

		pluginBundleVersionIdentifier = new PluginBundleVersionIdentifier(new PluginBundleIdentifier(model.getGroupId(), model.getArtifactId()), model.getVersion());
		
		File localFile = new File("C:\\Users\\Ruben de Laat\\.m2\\repository");

		List<org.apache.maven.model.Dependency> dependencies = model.getDependencies();
		Iterator<org.apache.maven.model.Dependency> it = dependencies.iterator();

		while (it.hasNext()) {
			org.apache.maven.model.Dependency depend = it.next();
			try {
				DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();

				DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
				locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
				// locator.addService( TransporterFactory.class,
				// FileTransporterFactory.class );
				// locator.addService( TransporterFactory.class,
				// HttpTransporterFactory.class );

				RepositorySystem system = locator.getService(RepositorySystem.class);

				LocalRepository localRepo = new LocalRepository(localFile);
				session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, localRepo));

				Dependency dependency2 = new Dependency(new DefaultArtifact(depend.getGroupId() + ":" + depend.getArtifactId() + ":" + depend.getVersion()), "compile");
				// RemoteRepository central = new
				// RemoteRepository.Builder("central", "default",
				// "http://repo1.maven.org/maven2/").build();

				CollectRequest collectRequest = new CollectRequest();
				collectRequest.setRoot(dependency2);
				DependencyNode node = system.collectDependencies(session, collectRequest).getRoot();

				DependencyRequest dependencyRequest = new DependencyRequest();
				dependencyRequest.setRoot(node);

				Path workspaceDir = Paths.get("..");
				bimServerDependencies.add(new org.bimserver.plugins.Dependency(workspaceDir.resolve("PluginBase/target/classes")));
				bimServerDependencies.add(new org.bimserver.plugins.Dependency(workspaceDir.resolve("Shared/target/classes")));

				try {
					system.resolveDependencies(session, dependencyRequest);
				} catch (DependencyResolutionException e) {
					// Ignore
				}

				PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
				node.accept(nlg);

				for (Artifact artifact : nlg.getArtifacts(false)) {
					Path jarFile = Paths.get(artifact.getFile().getAbsolutePath());

					LOGGER.info("Loading " + jarFile);

					// Path path =
					// projectRoot.getParent().resolve(nlg.getClassPath());

					DelegatingClassLoader depDelLoader = new DelegatingClassLoader(previous);
					loadDependencies(jarFile, depDelLoader);
					EclipsePluginClassloader depLoader = new EclipsePluginClassloader(depDelLoader, projectRoot);

					bimServerDependencies.add(new org.bimserver.plugins.Dependency(jarFile));

					previous = depLoader;
				}
			} catch (DependencyCollectionException e) {
				e.printStackTrace();
			}
		}

		delegatingClassLoader.add(previous);
		// Path libFolder = projectRoot.resolve("lib");
		// loadDependencies(libFolder, delegatingClassLoader);
		EclipsePluginClassloader pluginClassloader = new EclipsePluginClassloader(delegatingClassLoader, projectRoot);
		// pluginClassloader.dumpStructure(0);

		ResourceLoader resourceLoader = new ResourceLoader() {
			@Override
			public InputStream load(String name) {
				try {
					return Files.newInputStream(pluginFolder.resolve(name));
				} catch (IOException e) {
					e.printStackTrace();
				}
				return null;
			}
		};
		
		SPluginBundle sPluginBundle = new SPluginBundle();
		sPluginBundle.setOrganization(model.getOrganization().getName());
		sPluginBundle.setName(model.getName());
		
		SPluginBundleVersion sPluginBundleVersion = new SPluginBundleVersion();
		sPluginBundleVersion.setType(SPluginBundleType.MAVEN);
		sPluginBundleVersion.setGroupId(model.getGroupId());
		sPluginBundleVersion.setArtifactId(model.getArtifactId());
		sPluginBundleVersion.setVersion(model.getVersion());
		sPluginBundleVersion.setDescription(model.getDescription());
		sPluginBundleVersion.setRepository("local");
		sPluginBundleVersion.setType(SPluginBundleType.LOCAL);
		sPluginBundleVersion.setMismatch(false); // TODO

		sPluginBundle.setInstalledVersion(sPluginBundleVersion);

		return loadPlugins(pluginBundleVersionIdentifier, resourceLoader, pluginClassloader, projectRoot.toUri(), projectRoot.resolve("target/classes").toString(), pluginDescriptor, PluginSourceType.ECLIPSE_PROJECT, bimServerDependencies, sPluginBundle, sPluginBundleVersion);
	}
	
	public PluginBundle loadPluginsFromEclipseProject(Path projectRoot) throws PluginException {
		try {
			if (!Files.isDirectory(projectRoot)) {
				throw new PluginException("No directory: " + projectRoot.toString());
			}
			final Path pluginFolder = projectRoot.resolve("plugin");
			if (!Files.isDirectory(pluginFolder)) {
				throw new PluginException("No 'plugin' directory found in " + projectRoot.toString());
			}
			Path pluginFile = pluginFolder.resolve("plugin.xml");
			if (!Files.exists(pluginFile)) {
				throw new PluginException("No 'plugin.xml' found in " + pluginFolder.toString());
			}

			PluginDescriptor pluginDescriptor = getPluginDescriptor(Files.newInputStream(pluginFile));
			
			Path pomFile = projectRoot.resolve("pom.xml");
			Path packageFile = projectRoot.resolve("package.json");
			
			if (Files.exists(packageFile)) {
				return loadJavaScriptProject(projectRoot, packageFile, pluginFolder, pluginDescriptor);
			} else if (Files.exists(pomFile)) {
				return loadJavaProject(projectRoot, pomFile, pluginFolder, pluginDescriptor);
			} else {
				throw new PluginException("No pom.xml or package.json found in " + projectRoot.toString());
			}
		} catch (JAXBException e) {
			throw new PluginException(e);
		} catch (FileNotFoundException e) {
			throw new PluginException(e);
		} catch (IOException e) {
			throw new PluginException(e);
		} catch (XmlPullParserException e) {
			throw new PluginException(e);
		}
	}

	private PluginBundle loadJavaScriptProject(Path projectRoot, Path packageFile, Path pluginFolder, PluginDescriptor pluginDescriptor) {
		ObjectMapper objectMapper = new ObjectMapper();
		ObjectNode packageModel;
		try {
			packageModel = objectMapper.readValue(packageFile.toFile(), ObjectNode.class);
			SPluginBundle sPluginBundle = new SPluginBundle();
			
			if (!packageModel.has("organization")) {
				throw new PluginException("package.json does not contain 'organization'");
			}
			
			sPluginBundle.setOrganization(packageModel.get("organization").asText());
			sPluginBundle.setName(packageModel.get("name").asText());
			
			SPluginBundleVersion sPluginBundleVersion = new SPluginBundleVersion();
			sPluginBundleVersion.setType(SPluginBundleType.GITHUB);

			if (!packageModel.has("organization")) {
				throw new PluginException("package.json does not contain 'groupId'");
			}
			sPluginBundleVersion.setGroupId(packageModel.get("groupId").asText());
			
			if (!packageModel.has("organization")) {
				throw new PluginException("package.json does not contain 'artifactId'");
			}
			sPluginBundleVersion.setArtifactId(packageModel.get("artifactId").asText());
			
			if (!packageModel.has("organization")) {
				throw new PluginException("package.json does not contain 'version'");
			}
			sPluginBundleVersion.setVersion(packageModel.get("version").asText());
			
			if (!packageModel.has("organization")) {
				throw new PluginException("package.json does not contain 'description'");
			}
			sPluginBundleVersion.setDescription(packageModel.get("description").asText());
			
			sPluginBundleVersion.setRepository("local");
			sPluginBundleVersion.setType(SPluginBundleType.LOCAL);
			sPluginBundleVersion.setMismatch(false); // TODO
			
			sPluginBundle.setInstalledVersion(sPluginBundleVersion);
			
			PluginBundleVersionIdentifier pluginBundleVersionIdentifier = new PluginBundleVersionIdentifier(new PluginBundleIdentifier(packageModel.get("groupId").asText(), packageModel.get("artifactId").asText()), packageModel.get("version").asText());
			
			ResourceLoader resourceLoader = new ResourceLoader() {
				@Override
				public InputStream load(String name) {
					try {
						return Files.newInputStream(pluginFolder.resolve(name));
					} catch (IOException e) {
						e.printStackTrace();
					}
					return null;
				}
			};
			
			return loadPlugins(pluginBundleVersionIdentifier, resourceLoader, null, projectRoot.toUri(), null, pluginDescriptor, PluginSourceType.ECLIPSE_PROJECT, null, sPluginBundle, sPluginBundleVersion);
		} catch (JsonParseException e1) {
			e1.printStackTrace();
		} catch (JsonMappingException e1) {
			e1.printStackTrace();
		} catch (IOException e1) {
			e1.printStackTrace();
		} catch (PluginException e) {
			e.printStackTrace();
		}
		return null;
	}

	private void loadDependencies(Path libFile, DelegatingClassLoader classLoader) throws FileNotFoundException, IOException {
		if (libFile.getFileName().toString().toLowerCase().endsWith(".jar")) {
			FileJarClassLoader jarClassLoader = new FileJarClassLoader(this, classLoader, libFile);
			classLoader.add(jarClassLoader);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private PluginBundle loadPlugins(PluginBundleVersionIdentifier pluginBundleVersionIdentifier, ResourceLoader resourceLoader, ClassLoader classLoader, URI location, String classLocation, PluginDescriptor pluginDescriptor, PluginSourceType pluginType,
			List<org.bimserver.plugins.Dependency> dependencies, SPluginBundle sPluginBundle, SPluginBundleVersion sPluginBundleVersion) throws PluginException {
		PluginBundle pluginBundle = new PluginBundleImpl(pluginBundleVersionIdentifier, sPluginBundle, sPluginBundleVersion);
		
		if (classLoader != null && classLoader instanceof Closeable) {
			pluginBundle.addCloseable((Closeable) classLoader);
		}
		
		for (PluginImplementation pluginImplementation : pluginDescriptor.getImplementations()) {
			if (pluginImplementation.isEnabled()) {
				String interfaceClassName = pluginImplementation.getInterfaceClass().trim().replace("\n", "");
				try {
					Class interfaceClass = getClass().getClassLoader().loadClass(interfaceClassName);
					if (pluginImplementation.getImplementationClass() != null) {
						String implementationClassName = pluginImplementation.getImplementationClass().trim().replace("\n", "");
						try {
							Class implementationClass = classLoader.loadClass(implementationClassName);
							Plugin plugin = (Plugin) implementationClass.newInstance();
							pluginBundle.add(loadPlugin(pluginBundle, interfaceClass, location, classLocation, plugin, classLoader, pluginType, pluginImplementation, dependencies, plugin.getClass().getName()));
						} catch (NoClassDefFoundError e) {
							throw new PluginException("Implementation class '" + implementationClassName + "' not found", e);
						} catch (ClassNotFoundException e) {
							throw new PluginException("Implementation class '" + implementationClassName + "' not found in " + location, e);
						} catch (InstantiationException e) {
							throw new PluginException(e);
						} catch (IllegalAccessException e) {
							throw new PluginException(e);
						}
					} else if (pluginImplementation.getImplementationJson() != null) {
						ObjectMapper objectMapper = new ObjectMapper();
						ObjectNode settings = objectMapper.readValue(resourceLoader.load(pluginImplementation.getImplementationJson()), ObjectNode.class);
						JsonWebModule jsonWebModule = new JsonWebModule(settings);
						loadPlugin(pluginBundle, interfaceClass, location, classLocation, jsonWebModule, classLoader, pluginType, pluginImplementation, dependencies, settings.get("identifier").asText());
					}
				} catch (ClassNotFoundException e) {
					throw new PluginException("Interface class '" + interfaceClassName + "' not found", e);
				} catch (Error e) {
					throw new PluginException(e);
				} catch (JsonParseException e) {
					throw new PluginException(e);
				} catch (JsonMappingException e) {
					throw new PluginException(e);
				} catch (IOException e) {
					throw new PluginException(e);
				}
			} else {
				LOGGER.info("Plugin " + pluginImplementation.getImplementationClass() + " is disabled in plugin.xml");
			}
		}
		pluginBundleIdentifierToPluginBundle.put(pluginBundleVersionIdentifier.getPluginBundleIdentifier(), pluginBundle);
		pluginBundleVersionIdentifierToPluginBundle.put(pluginBundleVersionIdentifier, pluginBundle);

		return pluginBundle;
	}

	private PluginDescriptor getPluginDescriptor(InputStream inputStream) throws JAXBException {
		JAXBContext jaxbContext = JAXBContext.newInstance(PluginDescriptor.class);
		Unmarshaller unmarshaller = jaxbContext.createUnmarshaller();
		PluginDescriptor pluginDescriptor = (PluginDescriptor) unmarshaller.unmarshal(inputStream);
		return pluginDescriptor;
	}

	public void loadAllPluginsFromDirectoryOfJars(Path directory) throws PluginException, IOException {
		LOGGER.debug("Loading all plugins from " + directory.toString());
		if (!Files.isDirectory(directory)) {
			throw new PluginException("No directory: " + directory.toString());
		}
		for (Path file : PathUtils.list(directory)) {
			if (file.getFileName().toString().toLowerCase().endsWith(".jar")) {
				try {
					PluginBundleVersionIdentifier pluginBundleVersionIdentifier = PluginBundleVersionIdentifier.fromFileName(file.getFileName().toString());

					loadPluginsFromJar(pluginBundleVersionIdentifier, file, extractPluginBundleFromJar(file), extractPluginBundleVersionFromJar(file));
				} catch (PluginException e) {
					LOGGER.error("", e);
				}
			}
		}
	}

	public SPluginBundle extractPluginBundleFromJar(Path jarFilePath) throws PluginException {
		String filename = jarFilePath.getFileName().toString();
		PluginBundleVersionIdentifier pluginBundleVersionIdentifier = PluginBundleVersionIdentifier.fromFileName(filename);
		try (JarFile jarFile = new JarFile(jarFilePath.toFile())) {
			String pomLocation = "META-INF/maven/" + pluginBundleVersionIdentifier.getPluginBundleIdentifier().getGroupId() + "/" + pluginBundleVersionIdentifier.getPluginBundleIdentifier().getArtifactId() + "/" + "pom.xml";
			ZipEntry pomEntry = jarFile.getEntry(pomLocation);
			if (pomEntry == null) {
				throw new PluginException("No pom.xml found in JAR file " + jarFilePath.toString() + ", " + pomLocation);
			}
			MavenXpp3Reader mavenreader = new MavenXpp3Reader();

			Model model = mavenreader.read(jarFile.getInputStream(pomEntry));
			SPluginBundle sPluginBundle = new SPluginBundle();
			sPluginBundle.setOrganization(model.getOrganization().getName());
			sPluginBundle.setName(model.getName());
			return sPluginBundle;
		} catch (IOException e) {
			throw new PluginException(e);
		} catch (XmlPullParserException e) {
			throw new PluginException(e);
		}
	}
	
	public SPluginBundleVersion extractPluginBundleVersionFromJar(Path jarFilePath) throws PluginException {
		String filename = jarFilePath.getFileName().toString();
		PluginBundleVersionIdentifier pluginBundleVersionIdentifier = PluginBundleVersionIdentifier.fromFileName(filename);
		PluginBundleIdentifier pluginBundleIdentifier = pluginBundleVersionIdentifier.getPluginBundleIdentifier();
		try (JarFile jarFile = new JarFile(jarFilePath.toFile())) {
			ZipEntry pomEntry = jarFile.getEntry("META-INF/maven/" + pluginBundleIdentifier.getGroupId() + "/" + pluginBundleIdentifier.getArtifactId() + "/" + "pom.xml");
			if (pomEntry == null) {
				throw new PluginException("No pom.xml found in JAR file " + jarFilePath.toString());
			}
			MavenXpp3Reader mavenreader = new MavenXpp3Reader();

			Model model = mavenreader.read(jarFile.getInputStream(pomEntry));
			SPluginBundleVersion sPluginBundleVersion = new SPluginBundleVersion();
			sPluginBundleVersion.setType(SPluginBundleType.MAVEN);
			sPluginBundleVersion.setGroupId(model.getGroupId());
			sPluginBundleVersion.setArtifactId(model.getArtifactId());
			sPluginBundleVersion.setVersion(model.getVersion());
			sPluginBundleVersion.setDescription(model.getDescription());
			sPluginBundleVersion.setRepository("local");
			sPluginBundleVersion.setMismatch(false); // TODO
			return sPluginBundleVersion;
		} catch (IOException e) {
			throw new PluginException(e);
		} catch (XmlPullParserException e) {
			throw new PluginException(e);
		}
	}

	public PluginBundle loadPluginsFromJar(PluginBundleVersionIdentifier pluginBundleVersionIdentifier, Path file, SPluginBundle sPluginBundle, SPluginBundleVersion pluginBundleVersion) throws PluginException {
		PluginBundleIdentifier pluginBundleIdentifier = pluginBundleVersionIdentifier.getPluginBundleIdentifier();
		if (pluginBundleIdentifierToPluginBundle.containsKey(pluginBundleIdentifier)) {
			throw new PluginException("Plugin " + pluginBundleIdentifier.getHumanReadable() + " already loaded (version " + pluginBundleIdentifierToPluginBundle.get(pluginBundleIdentifier).getPluginBundleVersion().getVersion() + ")");
		}
		LOGGER.debug("Loading plugins from " + file.toString());
		if (!Files.exists(file)) {
			throw new PluginException("Not a file: " + file.toString());
		}
		try {
			final FileJarClassLoader jarClassLoader = new FileJarClassLoader(this, getClass().getClassLoader(), file);
			InputStream pluginStream = jarClassLoader.getResourceAsStream("plugin/plugin.xml");
			if (pluginStream == null) {
				throw new PluginException("No plugin/plugin.xml found in " + file.getFileName().toString());
			}
			PluginDescriptor pluginDescriptor = getPluginDescriptor(pluginStream);
			if (pluginDescriptor == null) {
				throw new PluginException("No plugin descriptor could be created");
			}
			LOGGER.debug(pluginDescriptor.toString());
			
			URI fileUri = file.toAbsolutePath().toUri();
			URI jarUri = new URI("jar:" + fileUri.toString());

			ResourceLoader resourceLoader = new ResourceLoader() {
				@Override
				public InputStream load(String name) {
					return jarClassLoader.getResourceAsStream(name);
				}
			};

			return loadPlugins(pluginBundleVersionIdentifier, resourceLoader, jarClassLoader, jarUri, file.toAbsolutePath().toString(), pluginDescriptor, PluginSourceType.JAR_FILE, new ArrayList<org.bimserver.plugins.Dependency>(), sPluginBundle, pluginBundleVersion);
		} catch (JAXBException e) {
			throw new PluginException(e);
		} catch (FileNotFoundException e) {
			throw new PluginException(e);
		} catch (IOException e) {
			throw new PluginException(e);
		} catch (URISyntaxException e) {
			throw new PluginException(e);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private <T> Map<PluginContext, T> getPlugins(Class<T> requiredInterfaceClass, boolean onlyEnabled) {
		Map<PluginContext, T> plugins = new HashMap<>();
		for (Class interfaceClass : implementations.keySet()) {
			if (requiredInterfaceClass.isAssignableFrom(interfaceClass)) {
				for (PluginContext pluginContext : implementations.get(interfaceClass)) {
					if (!onlyEnabled || pluginContext.isEnabled()) {
						plugins.put(pluginContext, (T) pluginContext.getPlugin());
					}
				}
			}
		}
		return plugins;
	}

	public Map<PluginContext, ObjectIDMPlugin> getAllObjectIDMPlugins(boolean onlyEnabled) {
		return getPlugins(ObjectIDMPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, RenderEnginePlugin> getAllRenderEnginePlugins(boolean onlyEnabled) {
		return getPlugins(RenderEnginePlugin.class, onlyEnabled);
	}

	public Map<PluginContext, StillImageRenderPlugin> getAllStillImageRenderPlugins(boolean onlyEnabled) {
		return getPlugins(StillImageRenderPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, QueryEnginePlugin> getAllQueryEnginePlugins(boolean onlyEnabled) {
		return getPlugins(QueryEnginePlugin.class, onlyEnabled);
	}

	public Map<PluginContext, SerializerPlugin> getAllSerializerPlugins(boolean onlyEnabled) {
		return getPlugins(SerializerPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, MessagingSerializerPlugin> getAllMessagingSerializerPlugins(boolean onlyEnabled) {
		return getPlugins(MessagingSerializerPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, MessagingStreamingSerializerPlugin> getAllMessagingStreamingSerializerPlugins(boolean onlyEnabled) {
		return getPlugins(MessagingStreamingSerializerPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, DeserializerPlugin> getAllDeserializerPlugins(boolean onlyEnabled) {
		return getPlugins(DeserializerPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, StreamingDeserializerPlugin> getAllStreamingDeserializerPlugins(boolean onlyEnabled) {
		return getPlugins(StreamingDeserializerPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, StreamingSerializerPlugin> getAllStreamingSeserializerPlugins(boolean onlyEnabled) {
		return getPlugins(StreamingSerializerPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, Plugin> getAllPlugins(boolean onlyEnabled) {
		return getPlugins(Plugin.class, onlyEnabled);
	}

	public PluginContext getPluginContext(Plugin plugin) {
		PluginContext pluginContext = pluginToPluginContext.get(plugin);
		if (pluginContext == null) {
			throw new RuntimeException("No plugin context found for " + plugin);
		}
		return pluginContext;
	}

	/**
	 * Load all plugins that can be found in the current classloader, if you
	 * downloaded a BIMserver client library and added certain plugins to the
	 * classpath, this method should be able to find and load them
	 */
//	@Deprecated
//	public void loadPluginsFromCurrentClassloader() {
//		try {
//			Enumeration<URL> resources = getClass().getClassLoader().getResources("plugin/plugin.xml");
//			while (resources.hasMoreElements()) {
//				URL url = resources.nextElement();
//				LOGGER.info("Loading " + url);
//				PluginDescriptor pluginDescriptor = getPluginDescriptor(url.openStream());
//
//				ResourceLoader resourceLoader = new ResourceLoader() {
//					@Override
//					public InputStream load(String name) {
//						return getClass().getClassLoader().getResourceAsStream(name);
//					}
//				};
//
//				// TODO
////				loadPlugins(new PluginBundleImpl(null, null), resourceLoader, getClass().getClassLoader(), url.toURI(), url.toString(), pluginDescriptor, PluginSourceType.INTERNAL, null);
//			}
//		} catch (IOException e) {
//			LOGGER.error("", e);
//		} catch (JAXBException e) {
//			LOGGER.error("", e);
//		} catch (PluginException e) {
//			LOGGER.error("", e);
//		} catch (URISyntaxException e) {
//			LOGGER.error("", e);
//		}
//	}

	public void enablePlugin(String name) {
		for (Set<PluginContext> pluginContexts : implementations.values()) {
			for (PluginContext pluginContext : pluginContexts) {
				if (pluginContext.getPlugin().getClass().getName().equals(name)) {
					pluginContext.setEnabled(true, true);
				}
			}
		}
	}

	public void disablePlugin(String name) {
		for (Set<PluginContext> pluginContexts : implementations.values()) {
			for (PluginContext pluginContext : pluginContexts) {
				if (pluginContext.getPlugin().getClass().getName().equals(name)) {
					pluginContext.setEnabled(false, true);
				}
			}
		}
	}

	public Plugin getPlugin(String identifier, boolean onlyEnabled) {
		for (Set<PluginContext> pluginContexts : implementations.values()) {
			for (PluginContext pluginContext : pluginContexts) {
				if (pluginContext.getIdentifier().equals(identifier)) {
					if (!onlyEnabled || pluginContext.isEnabled()) {
						return pluginContext.getPlugin();
					}
				}
			}
		}
		return null;
	}

	public boolean isEnabled(String className) {
		return getPlugin(className, true) != null;
	}

	public void addPluginChangeListener(PluginChangeListener pluginChangeListener) {
		pluginChangeListeners.add(pluginChangeListener);
	}

	public void notifyPluginStateChange(PluginContext pluginContext, boolean enabled) {
		for (PluginChangeListener pluginChangeListener : pluginChangeListeners) {
			pluginChangeListener.pluginStateChanged(pluginContext, enabled);
		}
	}

	public void notifyPluginInstalled(PluginContext pluginContext, SPluginInformation sPluginInformation) throws BimserverDatabaseException {
		for (PluginChangeListener pluginChangeListener : pluginChangeListeners) {
			pluginChangeListener.pluginInstalled(pluginContext, sPluginInformation);
		}
	}

	public Collection<DeserializerPlugin> getAllDeserializerPlugins(String extension, boolean onlyEnabled) {
		Collection<DeserializerPlugin> allDeserializerPlugins = getAllDeserializerPlugins(onlyEnabled).values();
		Iterator<DeserializerPlugin> iterator = allDeserializerPlugins.iterator();
		while (iterator.hasNext()) {
			DeserializerPlugin deserializerPlugin = iterator.next();
			if (!deserializerPlugin.canHandleExtension(extension)) {
				iterator.remove();
			}
		}
		return allDeserializerPlugins;
	}

	public DeserializerPlugin requireDeserializer(String extension) throws DeserializeException {
		Collection<DeserializerPlugin> allDeserializerPlugins = getAllDeserializerPlugins(extension, true);
		if (allDeserializerPlugins.size() == 0) {
			throw new DeserializeException("No deserializers found for type '" + extension + "'");
		} else {
			return allDeserializerPlugins.iterator().next();
		}
	}

	public Path getTempDir() {
		if (!Files.isDirectory(tempDir)) {
			try {
				Files.createDirectories(tempDir);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		return tempDir;
	}

	public PluginContext loadPlugin(PluginBundle pluginBundle, Class<? extends Plugin> interfaceClass, URI location, String classLocation, Plugin plugin, ClassLoader classLoader, PluginSourceType pluginType,
			PluginImplementation pluginImplementation, List<org.bimserver.plugins.Dependency> dependencies, String identifier) throws PluginException {
		LOGGER.debug("Loading plugin " + plugin.getClass().getSimpleName() + " of type " + interfaceClass.getSimpleName());
		if (!Plugin.class.isAssignableFrom(interfaceClass)) {
			throw new PluginException("Given interface class (" + interfaceClass.getName() + ") must be a subclass of " + Plugin.class.getName());
		}
		if (!implementations.containsKey(interfaceClass)) {
			implementations.put(interfaceClass, new LinkedHashSet<PluginContext>());
		}
		Set<PluginContext> set = (Set<PluginContext>) implementations.get(interfaceClass);
		try {
			PluginContext pluginContext = new PluginContext(this, pluginBundle, interfaceClass, classLoader, pluginType, pluginImplementation.getDescription(), location, plugin, pluginImplementation, classLocation, dependencies, identifier);
			pluginToPluginContext.put(plugin, pluginContext);
			set.add(pluginContext);
			return pluginContext;
		} catch (IOException e) {
			throw new PluginException(e);
		}
	}

	/**
	 * This method will initialize all the loaded plugins
	 * 
	 * @throws PluginException
	 */
	public void initAllLoadedPlugins() throws PluginException {
		LOGGER.debug("Initializig all loaded plugins");
		for (Class<? extends Plugin> pluginClass : implementations.keySet()) {
			Set<PluginContext> set = implementations.get(pluginClass);
			for (PluginContext pluginContext : set) {
				try {
					pluginContext.initialize();
				} catch (Throwable e) {
					LOGGER.error("", e);
					pluginContext.setEnabled(false, false);
				}
			}
		}
	}

	/*
	 * Returns a complete classpath for all loaded plugins
	 */
	public String getCompleteClassPath() {
		StringBuilder sb = new StringBuilder();
		if (baseClassPath != null) {
			sb.append(baseClassPath + File.pathSeparator);
		}
		for (Class<? extends Plugin> pluginClass : implementations.keySet()) {
			Set<PluginContext> set = implementations.get(pluginClass);
			for (PluginContext pluginContext : set) {
				sb.append(pluginContext.getClassLocation() + File.pathSeparator);
			}
		}
		return sb.toString();
	}

	public DeserializerPlugin getFirstDeserializer(String extension, Schema schema, boolean onlyEnabled) throws PluginException {
		Collection<DeserializerPlugin> allDeserializerPlugins = getAllDeserializerPlugins(extension, onlyEnabled);
		Iterator<DeserializerPlugin> iterator = allDeserializerPlugins.iterator();
		while (iterator.hasNext()) {
			DeserializerPlugin next = iterator.next();
			if (!next.getSupportedSchemas().contains(schema)) {
				iterator.remove();
			}
		}
		if (allDeserializerPlugins.size() == 0) {
			throw new PluginException("No deserializers with extension " + extension + " found");
		}
		return allDeserializerPlugins.iterator().next();
	}

	public ObjectIDMPlugin getObjectIDMByName(String className, boolean onlyEnabled) {
		return getPluginByClassName(ObjectIDMPlugin.class, className, onlyEnabled);
	}

	public RenderEnginePlugin getRenderEnginePlugin(String className, boolean onlyEnabled) {
		return getPluginByClassName(RenderEnginePlugin.class, className, onlyEnabled);
	}

	private <T extends Plugin> T getPluginByClassName(Class<T> clazz, String className, boolean onlyEnabled) {
		Collection<T> allPlugins = getPlugins(clazz, onlyEnabled).values();
		for (T t : allPlugins) {
			if (t.getClass().getName().equals(className)) {
				return t;
			}
		}
		return null;
	}

	public QueryEnginePlugin getQueryEngine(String className, boolean onlyEnabled) {
		return getPluginByClassName(QueryEnginePlugin.class, className, onlyEnabled);
	}

	public void loadAllPluginsFromEclipseWorkspace(Path file, boolean showExceptions) throws PluginException, IOException {
		if (file != null && Files.isDirectory(file)) {
			for (Path project : PathUtils.list(file)) {
				if (Files.isDirectory(project)) {
					Path pluginDir = project.resolve("plugin");
					if (Files.exists(pluginDir)) {
						Path pluginFile = pluginDir.resolve("plugin.xml");
						if (Files.exists(pluginFile)) {
							if (showExceptions) {
								loadPluginsFromEclipseProject(project);
							} else {
								loadPluginsFromEclipseProjectNoExceptions(project);
							}
						}
					}
				}
			}
		}
	}

	public void loadAllPluginsFromEclipseWorkspaces(Path directory, boolean showExceptions) throws PluginException, IOException {
		if (!Files.isDirectory(directory)) {
			return;
		}
		if (Files.exists(directory.resolve("plugin/plugin.xml"))) {
			if (showExceptions) {
				loadPluginsFromEclipseProject(directory);
			} else {
				loadPluginsFromEclipseProjectNoExceptions(directory);
			}
		}
		loadAllPluginsFromEclipseWorkspace(directory, showExceptions);
		for (Path workspace : PathUtils.list(directory)) {
			if (Files.isDirectory(workspace)) {
				loadAllPluginsFromEclipseWorkspace(workspace, showExceptions);
			}
		}
	}

	public Map<PluginContext, ModelMergerPlugin> getAllModelMergerPlugins(boolean onlyEnabled) {
		return getPlugins(ModelMergerPlugin.class, onlyEnabled);
	}

	public Map<PluginContext, ModelComparePlugin> getAllModelComparePlugins(boolean onlyEnabled) {
		return getPlugins(ModelComparePlugin.class, onlyEnabled);
	}

	public ModelMergerPlugin getModelMergerPlugin(String className, boolean onlyEnabled) {
		return getPluginByClassName(ModelMergerPlugin.class, className, onlyEnabled);
	}

	public ModelComparePlugin getModelComparePlugin(String className, boolean onlyEnabled) {
		return getPluginByClassName(ModelComparePlugin.class, className, onlyEnabled);
	}

	public Map<PluginContext, ServicePlugin> getAllServicePlugins(boolean onlyEnabled) {
		return getPlugins(ServicePlugin.class, onlyEnabled);
	}

	public ServicePlugin getServicePlugin(String className, boolean onlyEnabled) {
		return getPluginByClassName(ServicePlugin.class, className, onlyEnabled);
	}

	public ServiceFactory getServiceFactory() {
		return serviceFactory;
	}

	public void registerNewRevisionHandler(long uoid, ServiceDescriptor serviceDescriptor, NewRevisionHandler newRevisionHandler) {
		if (notificationsManagerInterface != null) {
			notificationsManagerInterface.registerInternalNewRevisionHandler(uoid, serviceDescriptor, newRevisionHandler);
		}
	}

	public void unregisterNewRevisionHandler(long uoid, ServiceDescriptor serviceDescriptor) {
		if (notificationsManagerInterface != null) {
			notificationsManagerInterface.unregisterInternalNewRevisionHandler(uoid, serviceDescriptor);
		}
	}

	public SServicesMap getServicesMap() {
		return servicesMap;
	}

	// public StillImageRenderPlugin getFirstStillImageRenderPlugin() throws
	// PluginException {
	// Collection<StillImageRenderPlugin> allPlugins =
	// getAllStillImageRenderPlugins(true).values();
	// if (allPlugins.size() == 0) {
	// throw new PluginException("No still image render plugins found");
	// }
	// StillImageRenderPlugin plugin = allPlugins.iterator().next();
	// if (!plugin.isInitialized()) {
	// plugin.init(this);
	// }
	// return plugin;
	//
	// }

	public Parameter getParameter(PluginContext pluginContext, String name) {
		return null;
	}

	public SerializerPlugin getSerializerPlugin(String className, boolean onlyEnabled) {
		return (SerializerPlugin) getPlugin(className, onlyEnabled);
	}

	public MessagingSerializerPlugin getMessagingSerializerPlugin(String className, boolean onlyEnabled) {
		return (MessagingSerializerPlugin) getPlugin(className, onlyEnabled);
	}

	public WebModulePlugin getWebModulePlugin(String className, boolean onlyEnabled) {
		return (WebModulePlugin) getPlugin(className, onlyEnabled);
	}

	public Map<PluginContext, WebModulePlugin> getAllWebPlugins(boolean onlyEnabled) {
		return getPlugins(WebModulePlugin.class, onlyEnabled);
	}

	public Map<PluginContext, ModelCheckerPlugin> getAllModelCheckerPlugins(boolean onlyEnabled) {
		return getPlugins(ModelCheckerPlugin.class, onlyEnabled);
	}

	public ModelCheckerPlugin getModelCheckerPlugin(String className, boolean onlyEnabled) {
		return getPluginByClassName(ModelCheckerPlugin.class, className, onlyEnabled);
	}

	public BimServerClientInterface getLocalBimServerClientInterface(AuthenticationInfo tokenAuthentication) throws ServiceException, ChannelConnectionException {
		return bimServerClientFactory.create(tokenAuthentication);
	}

	public void setBimServerClientFactory(BimServerClientFactory bimServerClientFactory) {
		this.bimServerClientFactory = bimServerClientFactory;
	}

	public void registerNewExtendedDataOnProjectHandler(long uoid, ServiceDescriptor serviceDescriptor, NewExtendedDataOnProjectHandler newExtendedDataHandler) {
		if (notificationsManagerInterface != null) {
			notificationsManagerInterface.registerInternalNewExtendedDataOnProjectHandler(uoid, serviceDescriptor, newExtendedDataHandler);
		}
	}

	public void registerNewExtendedDataOnRevisionHandler(long uoid, ServiceDescriptor serviceDescriptor, NewExtendedDataOnRevisionHandler newExtendedDataHandler) {
		if (notificationsManagerInterface != null) {
			notificationsManagerInterface.registerInternalNewExtendedDataOnRevisionHandler(uoid, serviceDescriptor, newExtendedDataHandler);
		}
	}

	public DeserializerPlugin getDeserializerPlugin(String pluginClassName, boolean onlyEnabled) {
		return getPluginByClassName(DeserializerPlugin.class, pluginClassName, onlyEnabled);
	}

	public StreamingDeserializerPlugin getStreamingDeserializerPlugin(String pluginClassName, boolean onlyEnabled) {
		return getPluginByClassName(StreamingDeserializerPlugin.class, pluginClassName, onlyEnabled);
	}

	public StreamingSerializerPlugin getStreamingSerializerPlugin(String pluginClassName, boolean onlyEnabled) {
		return getPluginByClassName(StreamingSerializerPlugin.class, pluginClassName, onlyEnabled);
	}

	public MetaDataManager getMetaDataManager() {
		return metaDataManager;
	}

	public void setMetaDataManager(MetaDataManager metaDataManager) {
		this.metaDataManager = metaDataManager;
	}

	public FileSystem getOrCreateFileSystem(URI uri) throws IOException {
		FileSystem fileSystem = null;
		try {
			fileSystem = FileSystems.getFileSystem(uri);
		} catch (FileSystemNotFoundException e) {
			Map<String, String> env = new HashMap<>();
			env.put("create", "true");
			fileSystem = FileSystems.newFileSystem(uri, env, null);
			LOGGER.debug("Created VFS for " + uri);
		}
		return fileSystem;
	}

	public MessagingStreamingSerializerPlugin getMessagingStreamingSerializerPlugin(String className, boolean onlyEnabled) {
		return (MessagingStreamingSerializerPlugin) getPlugin(className, onlyEnabled);
	}

	public List<SPluginInformation> getPluginInformationFromJar(Path file) throws PluginException, FileNotFoundException, IOException, JAXBException {
		try (JarFile jarFile = new JarFile(file.toFile())) {
			ZipEntry entry = jarFile.getEntry("plugin/plugin.xml");
			if (entry == null) {
				throw new PluginException("No plugin/plugin.xml found in " + file.getFileName().toString());
			}
			InputStream pluginStream = jarFile.getInputStream(entry);
			PluginDescriptor pluginDescriptor = getPluginDescriptor(pluginStream);
			if (pluginDescriptor == null) {
				throw new PluginException("No plugin descriptor could be created");
			}
			List<SPluginInformation> list = new ArrayList<>();
			for (PluginImplementation pluginImplementation : pluginDescriptor.getImplementations()) {
				SPluginInformation sPluginInformation = new SPluginInformation();
				sPluginInformation.setName(pluginImplementation.getImplementationClass());
				sPluginInformation.setDescription(pluginImplementation.getDescription());
				sPluginInformation.setType(getPluginTypeFromClass(pluginImplementation.getInterfaceClass()));
				list.add(sPluginInformation);
			}
			return list;
		}
	}

	public List<SPluginInformation> getPluginInformationFromPluginFile(Path file) throws PluginException, FileNotFoundException, IOException, JAXBException {
		List<SPluginInformation> list = new ArrayList<>();
		try (InputStream pluginStream = Files.newInputStream(file)) {
			PluginDescriptor pluginDescriptor = getPluginDescriptor(pluginStream);
			if (pluginDescriptor == null) {
				throw new PluginException("No plugin descriptor could be created");
			}
			for (PluginImplementation pluginImplementation : pluginDescriptor.getImplementations()) {
				SPluginInformation sPluginInformation = new SPluginInformation();
				sPluginInformation.setName(pluginImplementation.getImplementationClass());
				sPluginInformation.setDescription(pluginImplementation.getDescription());
				sPluginInformation.setType(getPluginTypeFromClass(pluginImplementation.getInterfaceClass()));
				list.add(sPluginInformation);
			}
		}
		return list;
	}
	
	public SPluginType getPluginTypeFromClass(String className) {
		switch (className) {
		case "org.bimserver.plugins.deserializers.DeserializerPlugin":
			return SPluginType.DESERIALIZER;
		case "org.bimserver.plugins.deserializers.StreamingDeserializerPlugin":
			return SPluginType.DESERIALIZER;
		case "org.bimserver.plugins.serializers.SerializerPlugin":
			return SPluginType.SERIALIZER;
		case "org.bimserver.plugins.serializers.StreamingSerializerPlugin":
			return SPluginType.SERIALIZER;
		case "org.bimserver.plugins.serializers.MessagingStreamingSerializerPlugin":
			return SPluginType.SERIALIZER;
		case "org.bimserver.plugins.serializers.MessagingSerializerPlugin":
			return SPluginType.SERIALIZER;
		case "org.bimserver.plugins.modelchecker.ModelCheckerPlugin":
			return SPluginType.MODEL_CHECKER;
		case "org.bimserver.plugins.modelmerger.ModelMergerPlugin":
			return SPluginType.MODEL_MERGER;
		case "org.bimserver.plugins.modelcompare.ModelComparePlugin":
			return SPluginType.MODEL_COMPARE;
		case "org.bimserver.plugins.objectidms.ObjectIDMPlugin":
			return SPluginType.OBJECT_IDM;
		case "org.bimserver.plugins.queryengine.QueryEnginePlugin":
			return SPluginType.QUERY_ENGINE;
		case "org.bimserver.plugins.services.ServicePlugin":
			return SPluginType.SERVICE;
		case "org.bimserver.plugins.renderengine.RenderEnginePlugin":
			return SPluginType.RENDER_ENGINE;
		case "org.bimserver.plugins.stillimagerenderer.StillImageRenderPlugin":
			return SPluginType.STILL_IMAGE_RENDER;
		case "org.bimserver.plugins.web.WebModulePlugin":
			return SPluginType.WEB_MODULE;
		}
		return null;
	}

	public PluginBundle install(PluginBundleVersionIdentifier pluginVersionIdentifier, SPluginBundle sPluginBundle, SPluginBundleVersion pluginBundleVersion, Path jarFile, List<SPluginInformation> plugins) throws Exception {
		Path target = pluginsDir.resolve(pluginVersionIdentifier.getFileName());
		if (Files.exists(target)) {
			throw new PluginException("This plugin has already been installed " + target.getFileName().toString());
		}
		Files.copy(jarFile, target);
		PluginBundle pluginBundle = null;
		// Stage 1, load all plugins from the JAR file and initialize them
		try {
			pluginBundle = loadPluginsFromJar(pluginVersionIdentifier, target, sPluginBundle, pluginBundleVersion);
			for (SPluginInformation sPluginInformation : plugins) {
				if (sPluginInformation.isEnabled()) {
					PluginContext pluginContext = pluginBundle.getPluginContext(sPluginInformation.getName());
					pluginContext.getPlugin().init(this);
				}
			}
		} catch (Exception e) {
			Files.delete(target);
			LOGGER.error("", e);
			throw e;
		}
		// Stage 2, if all went well, notify the listeners of this plugin, if
		// anything goes wrong in the notifications, the plugin bundle will be
		// uninstalled
		try {
			for (SPluginInformation sPluginInformation : plugins) {
				if (sPluginInformation.isEnabled()) {
					PluginContext pluginContext = pluginBundle.getPluginContext(sPluginInformation.getName());
					notifyPluginInstalled(pluginContext, sPluginInformation);
				}
			}
			return pluginBundle;
		} catch (Exception e) {
			uninstall(pluginVersionIdentifier);
			LOGGER.error("", e);
			throw e;
		}
	}

	public void uninstall(PluginBundleVersionIdentifier pluginBundleVersionIdentifier) {
		PluginBundle pluginBundle = pluginBundleVersionIdentifierToPluginBundle.get(pluginBundleVersionIdentifier);
		try {
			pluginBundle.close();
			pluginBundleVersionIdentifierToPluginBundle.remove(pluginBundleVersionIdentifier);
			pluginBundleIdentifierToPluginBundle.remove(pluginBundleVersionIdentifier.getPluginBundleIdentifier());

			for (PluginContext pluginContext : pluginBundle) {
				Set<PluginContext> set = implementations.get(pluginContext.getPluginInterface());
				set.remove(pluginContext);
			}
			
			Path target = pluginsDir.resolve(pluginBundleVersionIdentifier.getFileName());
			Files.delete(target);
			
			for (PluginContext pluginContext : pluginBundle) {
				notifyPluginUninstalled(pluginContext);
			}
		} catch (IOException e) {
			LOGGER.error("", e);
		}
	}

	private void notifyPluginUninstalled(PluginContext pluginContext) {
		for (PluginChangeListener pluginChangeListener : pluginChangeListeners) {
			pluginChangeListener.pluginUninstalled(pluginContext);
		}
	}

	public PluginBundle getPluginBundle(PluginBundleIdentifier pluginIdentifier) {
		return pluginBundleIdentifierToPluginBundle.get(pluginIdentifier);
	}

	public Collection<PluginBundle> getPluginBundles() {
		return pluginBundleVersionIdentifierToPluginBundle.values();
	}

	@Override
	public ObjectIDM getDefaultObjectIDM() throws ObjectIDMException {
		// TODO add a mechanism that can be used to ask a database what the
		// default plugin is
		return null;
	}
}