package eu.chainfire.libsuperuser;

import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for modifying SELinux policies, reducing the number of calls to a minimum.
 * <p>
 * Example usage:
 *
 * <pre>
 * <code>
 *
 * private class Policy extends eu.chainfire.libsuperuser.Policy {
 *     {@literal @}Override protected String[] getPolicies() {
 *         return new String[] {
 *             "allow sdcardd unlabeled dir { append create execute write relabelfrom link unlink ioctl getattr setattr read rename lock mounton quotaon swapon rmdir audit_access remove_name add_name reparent execmod search open }",
 *             "allow sdcardd unlabeled file { append create write relabelfrom link unlink ioctl getattr setattr read rename lock mounton quotaon swapon audit_access open }",
 *             "allow unlabeled unlabeled filesystem associate"
 *         };
 *     }
 * };
 * private Policy policy = new Policy();
 *
 * public void someFunctionNotCalledOnMainThread() {
 *     policy.inject();
 * }
 *
 * </code>
 * </pre>
 */
public abstract class Policy {
    /**
     * supolicy should be called as little as possible. We batch policies together. The command
     * line is guaranteed to be able to take 4096 characters. Reduce by a bit for supolicy itself.
     */
    private static final int MAX_POLICY_LENGTH = 4096 - 32;

    private static final Object synchronizer = new Object();
    private static volatile Boolean canInject = null;
    private static volatile boolean injected = false;

    /**
     * @return Have we injected our policies already?
     */
    public static boolean haveInjected() {
        return injected;
    }

    /**
     * Reset policies-have-been-injected state, if you really need to inject them again. Extremely
     * rare, you will probably never need this.
     */
    public static void resetInjected() {
        synchronized (synchronizer) {
            injected = false;
        }
    }

    /**
     * Detects availability of the supolicy tool. Only useful if Shell.SU.isSELinuxEnforcing()
     * returns true.
     *
     * @return canInject?
     */
    public static boolean canInject() {
        synchronized (synchronizer) {
            if (canInject != null) return canInject;

            canInject = false;

            // We are making the assumption here that if supolicy is called without parameters,
            // it will return output (such as a usage notice) on STDOUT (not STDERR) that contains
            // at least the word "supolicy". This is true at least for SuperSU.

            List<String> result = Shell.run("sh", new String[]{"supolicy"}, null, false);
            if (result != null) {
                for (String line : result) {
                    if (line.contains("supolicy")) {
                        canInject = true;
                        break;
                    }
                }
            }

            return canInject;
        }
    }

    /**
     * Reset cached can-inject state and force redetection on nect canInject() call
     */
    public static void resetCanInject() {
        synchronized (synchronizer) {
            canInject = null;
        }
    }

    /**
     * Override this method to return a array of strings containing the policies you want to inject.
     *
     * @return Policies to inject
     */
    protected abstract String[] getPolicies();

    /**
     * Transform the policies defined by getPolicies() into a set of shell commands
     *
     * @return Possibly empty List of commands, or null
     */
    protected List<String> getInjectCommands() {
        return getInjectCommands(true);
    }

    /**
     * Transform the policies defined by getPolicies() into a set of shell commands
     *
     * @param allowBlocking allow method to perform blocking I/O for extra checks
     * @return Possibly empty List of commands, or null
     */
    protected List<String> getInjectCommands(boolean allowBlocking) {
        synchronized (synchronizer) {
            // No reason to bother if we're in permissive mode
            if (!Shell.SU.isSELinuxEnforcing()) return null;

            // If we can't inject, no use continuing
            if (allowBlocking && !canInject()) return null;

            // Been there, done that
            if (injected) return null;

            // Retrieve policies
            String[] policies = getPolicies();
            if ((policies != null) && (policies.length > 0)) {
                List<String> commands = new ArrayList<String>();

                // Combine the policies into a minimal number of commands
                String command = "";
                for (String policy : policies) {
                    if ((command.length() == 0) || (command.length() + policy.length() + 3 < MAX_POLICY_LENGTH)) {
                        command = command + " \"" + policy + "\"";
                    } else {
                        commands.add("supolicy --live" + command);
                        command = "";
                    }
                }
                if (command.length() > 0) {
                    commands.add("supolicy --live" + command);
                }

                return commands;
            }

            // No policies
            return null;
        }
    }

    /**
     * Inject the policies defined by getPolicies(). Throws an exception if called from
     * the main thread in debug mode.
     */
    public void inject() {
        synchronized (synchronizer) {
            // Get commands that inject our policies
            List<String> commands = getInjectCommands();

            // Execute them, if any
            if ((commands != null) && (commands.size() > 0)) {
                Shell.SU.run(commands);
            }

            // We survived without throwing
            injected = true;
        }
    }

    /**
     * Inject the policies defined by getPolicies(). Throws an exception if called from
     * the main thread in debug mode if waitForIdle is true. If waitForIdle is false
     * however, it cannot be guaranteed the command was executed and the policies injected
     * upon return.
     *
     * @param shell       Interactive shell to execute commands on
     * @param waitForIdle wait for the command to complete before returning ?
     */
    public void inject(Shell.Interactive shell, boolean waitForIdle) {
        synchronized (synchronizer) {
            // Get commands that inject our policies
            List<String> commands = getInjectCommands(waitForIdle);

            // Execute them, if any
            if ((commands != null) && (commands.size() > 0)) {
                shell.addCommand(commands);
                if (waitForIdle) {
                    shell.waitForIdle();
                }
            }

            // We survived without throwing
            injected = true;
        }
    }
}
