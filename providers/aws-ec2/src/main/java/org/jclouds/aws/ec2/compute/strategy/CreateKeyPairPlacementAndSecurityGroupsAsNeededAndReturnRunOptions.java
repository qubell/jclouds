/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jclouds.aws.ec2.compute.strategy;

import static com.google.common.base.Predicates.and;
import static com.google.common.base.Predicates.or;

import java.util.Set;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Resource;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;
import javax.inject.Singleton;

import com.google.common.collect.ImmutableSet;
import org.jclouds.aws.ec2.compute.AWSEC2TemplateOptions;
import org.jclouds.aws.ec2.domain.RegionNameAndPublicKeyMaterial;
import org.jclouds.aws.ec2.functions.CreatePlacementGroupIfNeeded;
import org.jclouds.aws.ec2.options.AWSRunInstancesOptions;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.functions.GroupNamingConvention;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.compute.reference.ComputeServiceConstants;
import org.jclouds.ec2.compute.domain.RegionAndName;
import org.jclouds.ec2.compute.options.EC2TemplateOptions;
import org.jclouds.ec2.compute.strategy.CreateKeyPairAndSecurityGroupsAsNeededAndReturnRunOptions;
import org.jclouds.ec2.domain.KeyPair;
import org.jclouds.ec2.options.RunInstancesOptions;
import org.jclouds.logging.Logger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.cache.LoadingCache;

/**
 * 
 * @author Adrian Cole
 */
@Singleton
public class CreateKeyPairPlacementAndSecurityGroupsAsNeededAndReturnRunOptions extends
      CreateKeyPairAndSecurityGroupsAsNeededAndReturnRunOptions {
   @Resource
   @Named(ComputeServiceConstants.COMPUTE_LOGGER)
   protected Logger logger = Logger.NULL;
   @VisibleForTesting
   final LoadingCache<RegionAndName, String> placementGroupMap;
   @VisibleForTesting
   final CreatePlacementGroupIfNeeded createPlacementGroupIfNeeded;
   @VisibleForTesting
   final Function<RegionNameAndPublicKeyMaterial, KeyPair> importExistingKeyPair;

   final static Set<String> hardwareWithPlacementGroups = ImmutableSet.of(
           "c4.large", "c4.xlarge", "c4.2xlarge", "c4.4xlarge", "c4.8xlarge",
           "c3.large", "c3.xlarge", "c3.2xlarge", "c3.4xlarge", "c3.8xlarge",
           "cc2.8xlarge", "cg1.4xlarge", "g2.2xlarge", "cr1.8xlarge",
           "r3.large", "r3.xlarge", "r3.2xlarge", "r3.4xlarge", "r3.8xlarge",
           "hi1.4xlarge", "hs1.8xlarge", "i2.xlarge", "i2.2xlarge", "i2.4xlarge", "i2.8xlarge"
   );

   @Inject
   public CreateKeyPairPlacementAndSecurityGroupsAsNeededAndReturnRunOptions(
         Function<RegionAndName, KeyPair> makeKeyPair, ConcurrentMap<RegionAndName, KeyPair> credentialsMap,
         @Named("SECURITY") LoadingCache<RegionAndName, String> securityGroupMap,
         Provider<RunInstancesOptions> optionsProvider,
         @Named("PLACEMENT") LoadingCache<RegionAndName, String> placementGroupMap,
         CreatePlacementGroupIfNeeded createPlacementGroupIfNeeded,
         Function<RegionNameAndPublicKeyMaterial, KeyPair> importExistingKeyPair, 
         GroupNamingConvention.Factory namingConvention) {
      super(makeKeyPair, credentialsMap, securityGroupMap, optionsProvider, namingConvention);
      this.placementGroupMap = placementGroupMap;
      this.createPlacementGroupIfNeeded = createPlacementGroupIfNeeded;
      this.importExistingKeyPair = importExistingKeyPair;
   }

   public AWSRunInstancesOptions execute(String region, String group, Template template) {
      AWSRunInstancesOptions instanceOptions = AWSRunInstancesOptions.class
            .cast(super.execute(region, group, template));

      String placementGroupName = (hardwareWithPlacementGroups.contains(template.getHardware().getId())) ? createNewPlacementGroupUnlessUserSpecifiedOtherwise(
            region, group, template.getOptions()) : null;

      if (placementGroupName != null)
         instanceOptions.inPlacementGroup(placementGroupName);

      AWSEC2TemplateOptions awsTemplateOptions = AWSEC2TemplateOptions.class.cast(template.getOptions());
      if (awsTemplateOptions.isMonitoringEnabled())
         instanceOptions.enableMonitoring();
      if (awsTemplateOptions.getIAMInstanceProfileArn() != null)
         instanceOptions.withIAMInstanceProfileArn(awsTemplateOptions.getIAMInstanceProfileArn());
      if (awsTemplateOptions.getIAMInstanceProfileName() != null)
         instanceOptions.withIAMInstanceProfileName(awsTemplateOptions.getIAMInstanceProfileName());
      if (awsTemplateOptions.getPrivateIpAddress() != null)
         instanceOptions.withPrivateIpAddress(awsTemplateOptions.getPrivateIpAddress());
      if (awsTemplateOptions.getTenancy() != null)
         instanceOptions.withTenancy(awsTemplateOptions.getTenancy());
      if (awsTemplateOptions.getDedicatedHostId() != null)
         instanceOptions.withDedicatedHostId(awsTemplateOptions.getDedicatedHostId());

      return instanceOptions;
   }

   @VisibleForTesting
   String createNewPlacementGroupUnlessUserSpecifiedOtherwise(String region, String group, TemplateOptions options) {
      String placementGroupName = null;
      boolean shouldAutomaticallyCreatePlacementGroup = true;
      if (options instanceof EC2TemplateOptions) {
         placementGroupName = AWSEC2TemplateOptions.class.cast(options).getPlacementGroup();
         if (placementGroupName == null)
            shouldAutomaticallyCreatePlacementGroup = AWSEC2TemplateOptions.class.cast(options)
                  .shouldAutomaticallyCreatePlacementGroup();
      }
      if (placementGroupName == null && shouldAutomaticallyCreatePlacementGroup) {
         // placementGroupName must be unique within an account per
         // http://docs.amazonwebservices.com/AWSEC2/latest/UserGuide/index.html?using_cluster_computing.html
         placementGroupName = String.format("jclouds#%s#%s", group, region);
         RegionAndName regionAndName = new RegionAndName(region, placementGroupName);
         // make this entry as needed
         placementGroupMap.getUnchecked(regionAndName);
      }
      return placementGroupName;
   }

   @Override
   public String createNewKeyPairUnlessUserSpecifiedOtherwise(String region, String group, TemplateOptions options) {
      RegionAndName key = new RegionAndName(region, group);
      KeyPair pair;
      if (and(hasPublicKeyMaterial, or(doesntNeedSshAfterImportingPublicKey, hasLoginCredential)).apply(options)) {
         pair = importExistingKeyPair.apply(new RegionNameAndPublicKeyMaterial(region, group, options.getPublicKey()));
         options.dontAuthorizePublicKey();
         if (hasLoginCredential.apply(options))
            pair = pair.toBuilder().keyMaterial(options.getLoginPrivateKey()).build();
         credentialsMap.put(key, pair);
      } else {
         if (hasPublicKeyMaterial.apply(options)) {
            logger.warn("to avoid creating temporary keys in aws-ec2, use templateOption overrideLoginCredentialWith(id_rsa)");
         }
         return super.createNewKeyPairUnlessUserSpecifiedOtherwise(region, group, options);
      }
      return pair.getKeyName();
   }

   public static final Predicate<TemplateOptions> hasPublicKeyMaterial = new Predicate<TemplateOptions>() {

      @Override
      public boolean apply(TemplateOptions options) {
         return options.getPublicKey() != null;
      }

   };

   public static final Predicate<TemplateOptions> doesntNeedSshAfterImportingPublicKey = new Predicate<TemplateOptions>() {

      @Override
      public boolean apply(TemplateOptions options) {
         return options.getRunScript() == null && options.getPrivateKey() == null;
      }

   };

   public static final Predicate<TemplateOptions> hasLoginCredential = new Predicate<TemplateOptions>() {

      @Override
      public boolean apply(TemplateOptions options) {
         return options.getLoginPrivateKey() != null;
      }

   };

   @Override
   protected boolean userSpecifiedTheirOwnGroups(TemplateOptions options) {
      return options instanceof AWSEC2TemplateOptions
            && AWSEC2TemplateOptions.class.cast(options).getGroupIds().size() > 0
            || super.userSpecifiedTheirOwnGroups(options);
   }

   @Override
   protected void addSecurityGroups(String region, String group, Template template, RunInstancesOptions instanceOptions) {
      AWSEC2TemplateOptions awsTemplateOptions = AWSEC2TemplateOptions.class.cast(template.getOptions());
      AWSRunInstancesOptions awsInstanceOptions = AWSRunInstancesOptions.class.cast(instanceOptions);
      if (awsTemplateOptions.getGroupIds().size() > 0)
         awsInstanceOptions.withSecurityGroupIds(awsTemplateOptions.getGroupIds());
      String subnetId = awsTemplateOptions.getSubnetId();
      if (subnetId != null) {
         AWSRunInstancesOptions.class.cast(instanceOptions).withSubnetId(subnetId);
      } else {
         super.addSecurityGroups(region, group, template, instanceOptions);
      }
   }
}
