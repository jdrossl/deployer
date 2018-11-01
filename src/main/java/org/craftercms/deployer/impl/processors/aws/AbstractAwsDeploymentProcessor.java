/*
 * Copyright (C) 2007-2018 Crafter Software Corporation.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.craftercms.deployer.impl.processors.aws;

import org.apache.commons.configuration2.Configuration;
import org.apache.commons.lang3.StringUtils;
import org.craftercms.deployer.api.exceptions.DeployerException;
import org.craftercms.deployer.impl.processors.AbstractMainDeploymentProcessor;
import org.craftercms.deployer.utils.ConfigUtils;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;

/**
 * Base implementation of {@link org.craftercms.deployer.api.DeploymentProcessor} for all AWS related services
 * @author joseross
 * @param <B> {@link AwsClientBuilder} for the service that will be used
 * @param <T> AWS service interface that will be used
 */
public abstract class AbstractAwsDeploymentProcessor<B extends AwsClientBuilder, T>
    extends AbstractMainDeploymentProcessor {

    public static final String CONFIG_KEY_REGION = "region";
    public static final String CONFIG_KEY_ACCESS_KEY = "accessKey";
    public static final String CONFIG_KEY_SECRET_KEY = "secretKey";

    /**
     * AWS Region
     */
    protected String region;

    /**
     * AWS Access Key
     */
    protected String accessKey;

    /**
     * AWS Secret Key
     */
    protected String secretKey;

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(final Configuration config) throws DeployerException {
        super.init(config);
        if(config.containsKey(CONFIG_KEY_REGION)) {
            region = ConfigUtils.getStringProperty(config, CONFIG_KEY_REGION);
        }
        if(config.containsKey(CONFIG_KEY_ACCESS_KEY) && config.containsKey(CONFIG_KEY_SECRET_KEY)) {
            accessKey = ConfigUtils.getStringProperty(config, CONFIG_KEY_ACCESS_KEY);
            secretKey = ConfigUtils.getStringProperty(config, CONFIG_KEY_SECRET_KEY);
        }
    }

    /**
     * Sets the credentials (if provided) for the given {@link AwsClientBuilder}
     * @param builder the client builder
     */
    protected void setCredentials(AwsClientBuilder builder) {
        if(StringUtils.isNotEmpty(region)) {
            builder.withRegion(region);
        }
        if(StringUtils.isNotEmpty(accessKey) && StringUtils.isNotEmpty(secretKey)) {
            builder.withCredentials(new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey)));
        }
    }

    /**
     * Created a new instance of an {@link AwsClientBuilder} and configures the credentials
     * @return the client builder
     */
    protected T buildClient() {
        AwsClientBuilder<B, T> builder = createClientBuilder();
        setCredentials(builder);
        return builder.build();
    }

    /**
     * Creates an {@link AwsClientBuilder} for any service.
     * @return
     */
    protected abstract AwsClientBuilder<B, T> createClientBuilder();

}
