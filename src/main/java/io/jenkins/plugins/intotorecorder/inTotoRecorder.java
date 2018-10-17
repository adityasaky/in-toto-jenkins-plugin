/**
 *
 */
package io.jenkins.plugins.intotorecorder;

import io.github.in_toto.models.Link;
import io.github.in_toto.models.Artifact.ArtifactHash;
import io.github.in_toto.models.Artifact;

import io.github.in_toto.keys.Key;
import io.github.in_toto.keys.RSAKey;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;
import hudson.util.RunList;
import hudson.FilePath;
import hudson.FilePath.FileCallable;
import hudson.remoting.VirtualChannel;

import jenkins.MasterToSlaveFileCallable;

import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.lang.InterruptedException;

/**
 *
 * Jenkins recorder plugin to output signed link metadata for Jenkins pipeline
 * steps.
 *
 * @author SantiagoTorres
 */
public class inTotoRecorder extends Recorder {

    /**
     * Location of the key to load.
     *
     * If not defined signing will not be performed.
     */
    private String keyPath;

    /**
     * Name of the step to execute.
     *
     * If not defined, will default to step
     */
    private String stepName;

    /**
     * The host URL/URI where to post the in-toto metdata.
     *
     * Protocol information *must* be included.
     */
    private String transport;

    /**
     * Link metadata used to record this step
     *
     */
    private Link link;

    /**
     * Loaded key used to sign metadata
     */
    private Key key;

    /**
     * The current working directory (to be recorded as context).
     *
     */
    private FilePath cwd;

    @DataBoundConstructor
    public inTotoRecorder(String keyPath, String stepName, String transport)
    {

        /* Set a "sensible" step name if not defined */
        if (stepName == null || stepName.length() == 0)
            stepName = "step";
        this.stepName = stepName;

        /* notice how we can't do the same for the key, as that'd be a security
         * hazard */
        this.keyPath = keyPath;
        if (keyPath  != null && keyPath.length() != 0)
            loadKey(keyPath);

        /* The transport property will default to the current CWD, but we can't figure that one
         * just yet
         */
        this.transport = transport;
    }

    @Override
    public boolean prebuild(AbstractBuild<?,?> build, BuildListener listener)  {

        this.cwd = build.getWorkspace();
        String  cwdStr = this.cwd.toString();


        listener.getLogger().println("[in-toto] Recording state before build" + cwdStr);
        listener.getLogger().println("[in-toto] using step name: " + stepName);

        this.link = new Link(null, null, this.stepName, null, null, null);
        this.link.setMaterials(this.collectArtifacts(this.cwd));
        return true;
    }

    @Override
    public boolean perform(AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener) {

        this.link.setProducts(this.collectArtifacts(this.cwd));

        if (keyPath.length() == 0) {
            listener.getLogger().println("[in-toto] Warning! no keypath specified. Not signing...");
        } else {
            listener.getLogger().println("[in-toto] Signing with keyid: " + this.key.computeKeyId());
            signLink();
        }
        if (transport == null || transport.length() == 0) {
            listener.getLogger().println("[in-toto] No transport specified (or transport not supported)"
                    + " Dumping metadata to local directory");
        } else {
            listener.getLogger().println("[in-toto] Dumping metadata to: " + transport);
        }
        dumpLink();

        return true;
    }

    /* Private method that will help me publish metadata in a transport agnostic way. Most likely
     * by buffering and sending stuff over the wire once it's serialized to teporary directory
     */
    private void dumpLink() {
        String linkName = this.cwd.toString() + "/" + stepName + ".xxxx.link";
        this.link.dump(linkName);
    }

    private void signLink() {
        this.link.sign(this.key);
    }

    private void loadKey(String keyPath) {
        File keyFile = new File(keyPath);

        if (!keyFile.exists()) {
            throw new RuntimeException("this Signing keypath ("
                    + keyPath + ")does not exist!");
        }

        this.key = RSAKey.read(keyPath);
    }

    public String getKeyPath() {
        return this.keyPath;
    }

    public String getStepName() {
        return this.stepName;
    }

    public String getTransport() {
        return this.transport;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    private HashMap<String, ArtifactHash> collectArtifacts(FilePath path) {
        HashMap<String, ArtifactHash> result = null;
        try {
            result = path.act(new ArtifactCollector());
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e.toString());
        }
        return result;
    }

    /**
     * Descriptor for {@link inTotoRecorder}. Used as a singleton. The class is
     * marked as public so that it can be accessed from views.
     *
     *
     * See
     * <tt>src/main/resources/hudson/plugins/hello_world/HelloWorldBuilder/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        @SuppressWarnings("rawtypes")
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {return "in-toto provenance plugin";}
    }

    public BuildStepMonitor getRequiredMonitorService() {
         return BuildStepMonitor.NONE;
    }

    /**
     * Class to collect artifact hashes from remote hosts.
     *
     */
    private static final class ArtifactCollector
            extends MasterToSlaveFileCallable<HashMap<String, ArtifactHash>> {
        private static final long serialVersionUID = 1;

        @Override
        public HashMap<String, ArtifactHash> invoke(File f, VirtualChannel channel) {

            HashMap<String, ArtifactHash> result = new HashMap<String, ArtifactHash>();
            recurseAndCollect(f, result);
            return result;
        }

        private static void recurseAndCollect(File f, HashMap<String, ArtifactHash> hashmap) {

            if (f.exists() && f.isFile()) {
                Artifact artifact = new Artifact(f.toString());
                hashmap.put(artifact.getURI(), artifact.getArtifactHashes());
            } else if (f.exists() && f.isDirectory()) {
                File[] contents = f.listFiles();
                for (int i = 0; i < contents.length; i++) {
                    recurseAndCollect(contents[i], hashmap);
                }
            }
        }
    }
}
