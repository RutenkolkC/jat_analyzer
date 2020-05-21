package de.hhu.jat.main;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock.WriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonStructure;

import org.apache.maven.cli.MavenCli;
import org.apache.maven.lifecycle.internal.ReactorBuildStatus;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.model.building.DefaultModelBuilder;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.apache.maven.model.io.DefaultModelWriter;
import org.apache.maven.model.io.ModelWriter;
import org.apache.maven.project.MavenProject;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig.Host;
import org.eclipse.jgit.transport.SshSessionFactory;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.Task;
import org.takes.Request;
import org.takes.Response;
import org.takes.Take;
import org.takes.facets.fork.FkMethods;
import org.takes.facets.fork.FkRegex;
import org.takes.facets.fork.TkFork;
import org.takes.http.Exit;
import org.takes.http.FtBasic;
import org.takes.rq.form.RqFormBase;
import org.takes.rq.form.RqFormSmart;
import org.takes.rs.RsJson;
import org.takes.rs.RsWithStatus;
import org.takes.tk.TkFixed;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;


public class StartApp{
	
	public static ReentrantLock lock = new ReentrantLock();
	public static CountDownLatch SERVER_SHUTDOWN_LATCH = new CountDownLatch(0);
	public static Status PROGRESS = new Status();
	public static String GIT_DIRECTORY = "";
	public static volatile boolean SHOULD_STOP = false;
	
	//to do json this way is literally cancer. TODO: use gson
	public static class TaskStatus extends HashMap<String, String> implements RsJson.Source{
		public TaskStatus(String name) {
			this.put("name", name);
			this.put("percent", "0");
			this.put("elapsed-time", "0");
			this.put("status", "info");
			this.put("message", "");
		}
		@Override
		public JsonStructure toJson() throws IOException {
			var builder = Json.createObjectBuilder();
			this.entrySet().stream().forEach(entry->{builder.add(entry.getKey(),entry.getValue());});
			return builder.build();
		}
	}

	public static class Status extends ArrayList<TaskStatus> implements RsJson.Source{
		public Status() {
			this.add(new TaskStatus("Download Git Repo (Optional)"));
			this.add(new TaskStatus("Resolve directory"));
			this.add(new TaskStatus("METRICS SCAN & PROJECT MODIFICATION"));
			this.add(new TaskStatus("BUILDING PROJECT"));
			this.add(new TaskStatus("JQASSISTANT RUN"));
			this.add(new TaskStatus("STARTING NEO4J SERVER INSTANCE"));
		}
		@Override
		public JsonStructure toJson() throws IOException {
			var builder = Json.createArrayBuilder();
			this.stream().forEach(task->{try {builder.add(task.toJson());} 
										 catch (IOException e) {e.printStackTrace();}});
			return builder.build();
		}
	}
	
	public static void measureTimeAndUpdate(Runnable action,Consumer<Long> updateHandle,long updateStepSizeMillis){
		var actionLatch = new CountDownLatch(1);
		var waitLatch = new CountDownLatch(1);
		var start_t = System.nanoTime();
		var t = new Thread(()->{action.run();actionLatch.countDown();});
		t.start();
		var completion_t = new Thread(()->{try {
			actionLatch.await();
			var realFinishingTime = (System.nanoTime() - start_t);
			waitLatch.await();
			updateHandle.accept(realFinishingTime);
		} catch (InterruptedException e) {
			System.out.println("recieved interrupt");
		}});
		completion_t.start();
		while(t.isAlive()) {
			updateHandle.accept((System.nanoTime() - start_t));
			try {
				Thread.sleep(updateStepSizeMillis);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		waitLatch.countDown();
		try {
			completion_t.join();
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}
	
	

	public static void main(String[] args) throws Exception{
		SshSessionFactory.setInstance(new JschConfigSessionFactory() {
			  public void configure(Host hc, Session session) {
			    session.setConfig("StrictHostKeyChecking", "no");
				//JSch.setConfig("StrictHostKeyChecking", "no");
			    session.setConfig("PreferredAuthentications", "userauth.none");
			  }
			});
	    new FtBasic(
	    		new TkFork(
	    				new FkRegex("/analyze/status",(Take)(req->new RsJson(PROGRESS))),
	    				new FkRegex("/analyze/gitrepo",(Take)(req->new RsJson(Json.createObjectBuilder().add("gitDirectory", GIT_DIRECTORY).build()))),
	    				new FkRegex("/analyze/stop", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
	    									boolean canReset = lock.tryLock();
	    									if(canReset) {
	    										SERVER_SHUTDOWN_LATCH = new CountDownLatch(0);
												PROGRESS = new Status();
												lock.unlock();
	    									}
	    									return new RsWithStatus(canReset?200:500);}))),
	    				new FkRegex("/analyze/start", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
											var formdata = new RqFormBase(req);
	    									boolean isLocal = "true".equals(formdata.param("isLocal").iterator().next());
	    									var path = formdata.param("analyzePath").iterator().next();
	    									String buildTask = formdata.param("buildTask").iterator().next();
	    									String buildDirRelativePath = formdata.param("buildDirRelativePath").iterator().next();
	    									
	    									CompletableFuture<Boolean> success = new CompletableFuture<>();
	    									var a_t = new Thread(()->{
	    										success.complete(lock.tryLock());
	    										try {
													outer: if(success.get()) {
				    									SERVER_SHUTDOWN_LATCH = new CountDownLatch(1);
				    									CountDownLatch serverReadyLatch = new CountDownLatch(1);
				    									if(!isLocal) {
				    										if(SHOULD_STOP) break outer;
				    										String tempProjDir = downloadGitRepo(path);
				    										if(SHOULD_STOP) break outer;
				    										String projectDirectory = buildProject(tempProjDir,buildTask);
				    										GIT_DIRECTORY = tempProjDir;
				    										if(SHOULD_STOP) break outer;
															scanAndStartServer(SERVER_SHUTDOWN_LATCH,serverReadyLatch,projectDirectory+"/"+buildDirRelativePath);
				    									} else {
				    										if(SHOULD_STOP) break outer;
															scanAndStartServer(SERVER_SHUTDOWN_LATCH,serverReadyLatch,path);
				    									}
													}
												} catch (Exception e) {
													e.printStackTrace();
												} finally {
													lock.unlock();
												}
	    									});
	    									a_t.start();
	    									try {
												return new RsWithStatus(success.get()?200:500);
											} catch (Exception e) {
												return new RsWithStatus(500);
											}}))),
	    				new FkRegex("/analyze/reset", 
	    						new TkFork(
	    								new FkMethods("POST", req->{
	    									initiateReset();
	    									return new RsWithStatus(200);})))
	    				), 8079
	    	    ).start(Exit.NEVER);
	}
	private static void initiateReset() {
		System.out.println("RESET INITIATED!");
		SERVER_SHUTDOWN_LATCH.countDown();
		SHOULD_STOP = true;
		new Thread(()->{
			lock.lock();
			SERVER_SHUTDOWN_LATCH = new CountDownLatch(0);
			PROGRESS = new Status();
			SHOULD_STOP = false;
			System.out.println("RESET COMPLETE");
			lock.unlock();
		}).start();
	}
	private static String downloadGitRepo(String path) throws Exception {
		try{Files.createDirectory(Path.of("/tmp/jat"));}catch(Exception e) {}
		var tmp_dir = Files.createTempDirectory(Path.of("/tmp/jat"),"jat_git_clone");
		measureTimeAndUpdate(()->{
			try {
				Git git = Git.cloneRepository()
						.setURI(path)
						.setDirectory(tmp_dir.toFile())
						.call();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, nano_t->{
			PROGRESS.get(0).put("elapsed-time", nano_t / 1e9 + "");
		}, 237);
		finishEntry(PROGRESS.get(0));
		return tmp_dir.toAbsolutePath().toString();
	}
	/**
	 * 
	 * @param path
	 * @param buildTask
	 * @return Project Path. May differ from download repo if project isnt on top level of file system
	 * @throws IOException 
	 */
	private static String buildProject(String path, String buildTask) throws IOException {
		System.out.println("BUILDING PROJECT");
		var maybeProj = Files.walk(Paths.get(path))
			.filter(Files::isRegularFile)
			.filter(f->"pom.xml".equals(f.getFileName().toString())
					 ||"build.gradle".equals(f.getFileName().toString()))
			.findAny();
		
		if(maybeProj.isPresent()) {
			var proj = maybeProj.get();
			var filename = proj.getFileName().toString();
			System.out.println("FOUND PROJECT AT:"+proj.toAbsolutePath().toString());
			switch(filename) {
			case "pom.xml": buildMaven(proj.getParent(),buildTask); break;
			case "build.gradle": buildGradle(proj.getParent(),buildTask); break;
			default: noBuildSystemFound();
			}
			System.out.println("Build System has finished.\nProject directory is:"+proj.getParent().toAbsolutePath().toString());

			return proj.getParent().toAbsolutePath().toString();
		}else {			
			return null;
		}
	}
	private static void noBuildSystemFound() {
		PROGRESS.get(3).put("status", "danger");
	}
	private static void buildMaven(Path proj, String buildTask) {
		var project_root = proj.toAbsolutePath().toString();
		try {
			measureTimeAndUpdate(
				()->{
					System.setProperty("maven.multiModuleProjectDirectory", project_root);
					MavenCli maven = new MavenCli();
					maven.doMain(new String[]{"-Dmaven.multiModuleProjectDirectory=" + project_root,"jqassistant:server"}, project_root, System.out, System.out);
				}, 
				(nano_t)->{
					PROGRESS.get(3).put("elapsed-time", nano_t / 1e9 + "");
				},
				237);
			finishEntry(PROGRESS.get(3));
		} catch (Exception e) {
			PROGRESS.get(3).put("status", "danger");
			PROGRESS.get(3).put("message", e.getMessage());
			e.printStackTrace();
		}
	}
	public static void buildGradle(Path proj,String task) {
		System.out.println("BUILDING GRADLE PROJECT");
		try {
			measureTimeAndUpdate(
				()->{
					System.out.println("ESTABLISHING GRADLECONNECTOR");
					var conn = GradleConnector.newConnector().forProjectDirectory(proj.toFile()).connect();
					System.out.println("RUNNING GRADLE");
					conn.newBuild().forTasks(task).setStandardOutput(System.out).run();
					System.out.println("GRADLE FINISHED");
				}, 
				(nano_t)->{
					PROGRESS.get(3).put("elapsed-time", nano_t / 1e9 + "");
				},
				237);
			System.out.println("FINISHING STATUS ENTRY");
			finishEntry(PROGRESS.get(3));
		} catch (Exception e) {
			PROGRESS.get(3).put("status", "danger");
			PROGRESS.get(3).put("message", e.getMessage());
			e.printStackTrace();
		}
	}
	public static Plugin jqaplugin() {
		Plugin p = new Plugin();
		p.setGroupId("com.buschmais.jqassistant");
		p.setArtifactId("jqassistant-maven-plugin");
		p.setVersion("1.7.0-MS3");
		PluginExecution e = new PluginExecution();
		e.setPhase("verify");
		e.addGoal("scan");
		p.addExecution(e);
		return p;
	}

	public static void finishEntry(TaskStatus s) {
		s.put("percent", "100");
		s.put("status", "success");
	}

	/**
	 * scan a directory and start an embedded JQAssistant Neo4Jv3 server instance.
	 * CAUTION: Will not return unless a newline is written asynchronously to the 'in' InputStream
	 * 
	 * Will create a temporary maven project with the jqassistant-maven-plugin.
	 * The inputDirectory will be included in the 'ScanIncludes' configuration of the plugin.
	 * 
	 * @param inputDirectory The directory to scan
	 * @param serverShutdownLatch Server shutdown latch. Async count down to 0 to stop the server.
	 * @param serverReadyLatch Server ready latch. Gets count down by the function to indicate that the server has started.
	 * @throws Exception
	 */
	public static void scanAndStartServer(CountDownLatch serverShutdownLatch,CountDownLatch serverReadyLatch,String... inputDirectory) throws Exception{
		finishEntry(PROGRESS.get(0));
		finishEntry(PROGRESS.get(1));
		finishEntry(PROGRESS.get(2));
		
		String pom_content = loadCustomPom(inputDirectory);
		Path mvn_proj = createTempMavenProject(pom_content);
		
		measureTimeAndUpdate(()->{
			try {
				executeMavenInstall(mvn_proj.toAbsolutePath().toString());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}, nano_t->{
			PROGRESS.get(4).put("elapsed-time", nano_t/1e9+"");
		}, 237);
		finishEntry(PROGRESS.get(4));
		if(SHOULD_STOP)return;
		System.out.println("--- JQASSISTANT SCAN COMPLETE ---");
		System.out.println("--- STARTING JQASSISTANT EMBEDDED NEO4J-SERVER ---");
		startServer(mvn_proj.toString(),serverShutdownLatch,serverReadyLatch);
	}
	public static void scanAndStartServer(CountDownLatch latch,CountDownLatch serverReadyLatch,String inputDirectory) throws Exception{
		scanAndStartServer(latch,serverReadyLatch,new String[]{inputDirectory});
	}
	public static String loadCustomPom(String inputDirectory) {
		return loadCustomPom(new String[]{inputDirectory});
	}
	public static String loadCustomPom(String... inputDirectory) {
		System.out.println("loading custom pom with input directories:");
		Arrays.stream(inputDirectory).forEach(System.out::println);
		final String pom_file_path = ClassLoader.getSystemClassLoader().getResource("./pom_wrap.xml").getFile();
		String pom_content = null;
		try (BufferedReader reader = new BufferedReader(new FileReader(pom_file_path))){
			  pom_content = reader.lines().collect(Collectors.joining("\n"));
			  
			  String scanincludes = Arrays.stream(inputDirectory).map(dir->("<scanInclude><path>"+dir+"</path></scanInclude>")).collect(Collectors.joining("\n"));
			  
			  pom_content = pom_content.replace("__INPUT_DIRECTORY__", scanincludes);
		} catch (Exception e1) {
			e1.printStackTrace();
			throw new RuntimeException("Could not load POM.");
		}
		return pom_content;
	}

	public static Path createTempMavenProject(String pom_content) {
		Path tmp_dir = null;
		try {
			tmp_dir = Files.createTempDirectory("jat_mvn_wrap");

			File pom_xml = new File(tmp_dir.toFile(), "pom.xml");
			pom_xml.createNewFile();
			
			try(FileWriter writer = new FileWriter(pom_xml)){
				writer.write(pom_content);
			}
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("Could not create temporary maven project.");
		}
		System.out.println("Created Maven Wrapper Project at:\n"+tmp_dir);
		
		return tmp_dir;
	}
	public static void executeMavenInstall(String project_root) throws Exception {
		System.setProperty("maven.multiModuleProjectDirectory", project_root);

        MavenCli maven = new MavenCli();
        maven.doMain(new String[]{"-Dmaven.multiModuleProjectDirectory=" + project_root,"install"}, project_root, System.out, System.out);
    }
	/**
	 * Starts the server and stops it when a latch reaches zero. Blocks.
	 * @param project_root project path
	 * @param latch CoutDownLatch to control the shutdown of the server. When the latch is count down to zero the server will stop.
	 *  
	 */
	public static void startServer(String project_root,CountDownLatch latch,CountDownLatch serverReadyLatch) throws Exception {
		
		PipedInputStream in = new PipedInputStream();
		System.setIn(in);
		PipedOutputStream out = new PipedOutputStream(in);
		ByteArrayOutputStream os =  new ByteArrayOutputStream();
		PrintStream customOutStream = new PrintStream(os);
		long start_t = System.nanoTime();
		var a_t = new Thread(()-> {
			try {
				Thread.sleep(2000);
				while (true) {
					List<String> lines = os.toString().lines().collect(Collectors.toList());
					if(lines.isEmpty()) {
						Thread.sleep(500);
						continue;
					}
					var last = lines.get(lines.size()-1);
					if(last.contains("com.buschmais.jqassistant.scm.maven.ServerMojo - Press <Enter> to finish.") || SHOULD_STOP) {
						break;
					}else {
						Thread.sleep(500);
					}
				}
				serverReadyLatch.countDown();
				PROGRESS.get(5).put("elapsed-time", (System.nanoTime() - start_t) / 1e9 + "");
				finishEntry(PROGRESS.get(5));
				latch.await();

				out.write(49);
			} catch (IOException e) {
				e.printStackTrace();
			} catch (InterruptedException e) {
				System.out.println("recieved interrupt");
			} finally {
				try {
					out.close();
				} catch (IOException e) {
					e.printStackTrace();
					System.exit(-1);
				}
			}
		});
		a_t.start();
		if(SHOULD_STOP) return;
		System.setProperty("maven.multiModuleProjectDirectory", project_root);
		MavenCli maven = new MavenCli();
		maven.doMain(new String[]{"-Dmaven.multiModuleProjectDirectory=" + project_root,"jqassistant:server"}, project_root, customOutStream, customOutStream);
		//maven.doMain(new String[]{"-Dmaven.multiModuleProjectDirectory=" + project_root,"jqassistant:server"}, project_root, System.out, System.out);
        System.out.println("SERVER STOPPED");
	}
}
