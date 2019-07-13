/*
 * SonarQube Scanner for Jenkins
 * Copyright (C) 2007-2019 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package hudson.plugins.sonar;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;

import hudson.CopyOnWrite;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Api;
import hudson.model.UnprotectedRootAction;
import hudson.plugins.sonar.SonarPublisher.DescriptorImpl;
import hudson.plugins.sonar.utils.Logger;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.HttpResponses;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;

/**
 * Since 2.4 The global configuration was migrated from SonarPublisher to this
 * component.
 */
@Extension(ordinal = 100)
public class SonarGlobalConfiguration extends GlobalConfiguration implements UnprotectedRootAction {
    @CopyOnWrite
    private volatile SonarInstallation[] installations = new SonarInstallation[0];
    private volatile boolean buildWrapperEnabled = false;
    boolean dataMigrated = false;
    private boolean credentialsMigrated;

    public SonarGlobalConfiguration() {
        load();
    }

    /**
     * @return all configured {@link hudson.plugins.sonar.SonarInstallation}
     */
    public SonarInstallation[] getInstallations() {
        return installations;
    }

    public boolean isBuildWrapperEnabled() {
        return buildWrapperEnabled;
    }

    public void setInstallations(SonarInstallation... installations) {
        this.installations = installations;
        save();
    }

    public void setBuildWrapperEnabled(boolean enabled) {
        this.buildWrapperEnabled = enabled;
        save();
    }

    /**
     * Attempts to migrate data from SonarPublished, which was previously
     * holding the global configuration. It will refuse to migrate if a
     * SonarQube installation already exists in this class. Migration will only
     * be attempted once.
     */
    @SuppressWarnings("deprecation")
    @Initializer(after = InitMilestone.JOB_LOADED)
    public void migrateData() {
        if (dataMigrated) {
            return;
        }
        Optional<DescriptorImpl> publisherOpt = ExtensionList.lookup(SonarPublisher.DescriptorImpl.class).stream()
                .findFirst();
        // SonarPublisher might be missing if Maven plugin is disabled or not
        // installed
        publisherOpt.ifPresent(publisher -> {
            if (publisher.getDeprecatedInstallations() != null && publisher.getDeprecatedInstallations().length > 0) {
                if (ArrayUtils.isEmpty(this.installations)) {
                    this.installations = publisher.getDeprecatedInstallations();
                    this.buildWrapperEnabled = publisher.isDeprecatedBuildWrapperEnabled();
                    save();
                } else {
                    Logger.LOG.warning(
                            "SonarQube server configurations exist in both deprecated SonarPublisher and SonarGlobalConfiguration. Deleting deprecated configuration..");
                }

                publisher.deleteGlobalConfiguration();
            }
        });

        dataMigrated = true;
        save();
    }

    @Initializer(after = InitMilestone.JOB_LOADED)
    public void migrateCredentials() {
        if (credentialsMigrated) {
            return;
        }

        Arrays.stream(this.installations).forEach(SonarInstallation::migrateTokenToCredential);

        credentialsMigrated = true;
        save();
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) {
        List<SonarInstallation> list = req.bindJSONToList(SonarInstallation.class, json.get("inst"));
        boolean enableBuildWrapper = json.getBoolean("enableBuildWrapper");
        setInstallations(list.toArray(new SonarInstallation[list.size()]));
        setBuildWrapperEnabled(enableBuildWrapper);

        return true;
    }

    public FormValidation doCheckMandatory(@QueryParameter String value) {
        return StringUtils.isBlank(value) ? FormValidation.error(Messages.SonarGlobalConfiguration_MandatoryProperty())
                : FormValidation.ok();
    }

    public static SonarGlobalConfiguration get() {
        return GlobalConfiguration.all().get(SonarGlobalConfiguration.class);
    }

    @SuppressWarnings("unused")
    public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId) {
        if (!Jenkins.getInstance().hasPermission(Jenkins.ADMINISTER)) {
            return new StandardListBoxModel().includeCurrentValue(credentialsId);
        }

        return new StandardListBoxModel().includeEmptyValue().includeMatchingAs(ACL.SYSTEM, Jenkins.getInstance(),
                StringCredentials.class, Collections.emptyList(), CredentialsMatchers.always());
    }

    @Override
    public String getIconFileName() {
        return null;
    }

    @Override
    public String getUrlName() {
        return "sonar";
    }

    public Api getApi() {
        return new Api(this);
    }

    @RequirePOST
    @Restricted(NoExternalUse.class)
    @SuppressWarnings("unused") // stapler web method
    public HttpResponse doCreate(StaplerRequest req) throws ServletException, IOException {
        StringBuilder stringBuilder = new StringBuilder();
        String line = "";
        while ((line = req.getReader().readLine()) != null) {
            stringBuilder.append(line);
            System.out.println(line);
        }

        JSONObject jsonObject = JSONObject.fromObject(stringBuilder.toString());
        SonarInstallation sonarInstallation = (SonarInstallation) JSONObject.toBean(jsonObject,
                SonarInstallation.class);

        StandardListBoxModel options = (StandardListBoxModel) new StandardListBoxModel().includeEmptyValue()
                .includeMatchingAs(ACL.SYSTEM, Jenkins.getInstance(), StringCredentials.class, Collections.emptyList(),
                        CredentialsMatchers.always());

        boolean contain = false;
        Iterator<Option> iterator = options.iterator();
        while (iterator.hasNext()) {
            Option op = iterator.next();
            if (op.value.equals(sonarInstallation.getCredentialsId())) {
                contain = true;
                break;
            }
        }

        if (sonarInstallation.getCredentialsId().trim().equals("")) {
            contain = true;
        }

        if (!contain) {
            return HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "credentialId does not exists");
        }

        List<SonarInstallation> list = new ArrayList<SonarInstallation>();
        for (SonarInstallation sonar : this.installations) {
            list.add(sonar);
        }
        list.add(sonarInstallation);

        SonarInstallation[] sonars = list.toArray(new SonarInstallation[list.size()]);
        setInstallations(sonars);

        return HttpResponses.ok();
    }

}
