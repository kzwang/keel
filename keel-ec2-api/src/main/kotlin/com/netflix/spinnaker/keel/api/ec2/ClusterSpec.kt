package com.netflix.spinnaker.keel.api.ec2

import com.netflix.spinnaker.keel.api.ClusterDeployStrategy
import com.netflix.spinnaker.keel.api.ComputeResourceSpec
import com.netflix.spinnaker.keel.api.Locatable
import com.netflix.spinnaker.keel.api.Locations
import com.netflix.spinnaker.keel.api.Moniker
import com.netflix.spinnaker.keel.api.Monikered
import com.netflix.spinnaker.keel.api.RedBlack
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.SubnetAwareRegionSpec
import com.netflix.spinnaker.keel.api.UnhappyControl
import com.netflix.spinnaker.keel.api.artifacts.ArtifactType
import com.netflix.spinnaker.keel.api.artifacts.DEBIAN
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec.ServerGroupSpec
import com.netflix.spinnaker.keel.api.ec2.ServerGroup.Health
import com.netflix.spinnaker.keel.api.ec2.ServerGroup.LaunchConfiguration
import java.time.Duration

/**
 * Transforms a [ClusterSpec] into a concrete model of server group desired states.
 */
fun ClusterSpec.resolve(): Set<ServerGroup> =
  locations.regions.map {
    ServerGroup(
      name = moniker.toString(),
      location = Location(
        account = locations.account,
        region = it.name,
        vpc = locations.vpc ?: error("No vpc supplied or resolved"),
        subnet = locations.subnet ?: error("No subnet purpose supplied or resolved"),
        availabilityZones = it.availabilityZones
      ),
      launchConfiguration = resolveLaunchConfiguration(it),
      capacity = resolveCapacity(it.name),
      dependencies = resolveDependencies(it.name),
      health = resolveHealth(it.name),
      scaling = resolveScaling(it.name),
      tags = defaults.tags + overrides[it.name]?.tags,
      artifactName = artifactName,
      artifactVersion = artifactVersion
    )
  }
    .toSet()

private fun ClusterSpec.resolveLaunchConfiguration(region: SubnetAwareRegionSpec): LaunchConfiguration {
  val image = checkNotNull(overrides[region.name]?.launchConfiguration?.image
    ?: defaults.launchConfiguration?.image) { "No image resolved / specified for ${region.name}" }
  return LaunchConfiguration(
    appVersion = image.appVersion,
    baseImageVersion = image.baseImageVersion,
    imageId = image.id,
    instanceType = checkNotNull(overrides[region.name]?.launchConfiguration?.instanceType
      ?: defaults.launchConfiguration?.instanceType) {
      "No instance type resolved for $id (region ${region.name}) and cannot determine a default"
    },
    ebsOptimized = checkNotNull(overrides[region.name]?.launchConfiguration?.ebsOptimized
      ?: defaults.launchConfiguration?.ebsOptimized
      ?: LaunchConfiguration.DEFAULT_EBS_OPTIMIZED),
    iamRole = checkNotNull(overrides[region.name]?.launchConfiguration?.iamRole
      ?: defaults.launchConfiguration?.iamRole
      ?: LaunchConfiguration.defaultIamRoleFor(moniker.app)),
    keyPair = checkNotNull(overrides[region.name]?.launchConfiguration?.keyPair
      ?: defaults.launchConfiguration?.keyPair) {
      "No keypair resolved for $id (region ${region.name}) and cannot determine a default"
    },
    instanceMonitoring = overrides[region.name]?.launchConfiguration?.instanceMonitoring
      ?: defaults.launchConfiguration?.instanceMonitoring
      ?: LaunchConfiguration.DEFAULT_INSTANCE_MONITORING,
    ramdiskId = overrides[region.name]?.launchConfiguration?.ramdiskId
      ?: defaults.launchConfiguration?.ramdiskId
  )
}

fun ClusterSpec.resolveCapacity(region: String) =
  overrides[region]?.capacity ?: defaults.capacity ?: Capacity(1, 1, 1)

private fun ClusterSpec.resolveScaling(region: String): Scaling =
  Scaling(
    suspendedProcesses = defaults.scaling?.suspendedProcesses + overrides[region]?.scaling?.suspendedProcesses,
    targetTrackingPolicies = defaults.scaling?.targetTrackingPolicies +
      overrides[region]?.scaling?.targetTrackingPolicies,
    stepScalingPolicies = defaults.scaling?.stepScalingPolicies + overrides[region]?.scaling?.stepScalingPolicies
  )

private fun ClusterSpec.resolveDependencies(region: String): ClusterDependencies =
  ClusterDependencies(
    loadBalancerNames = defaults.dependencies?.loadBalancerNames + overrides[region]?.dependencies?.loadBalancerNames,
    securityGroupNames = defaults.dependencies?.securityGroupNames + overrides[region]?.dependencies?.securityGroupNames,
    targetGroups = defaults.dependencies?.targetGroups + overrides[region]?.dependencies?.targetGroups
  )

private fun ClusterSpec.resolveHealth(region: String): Health {
  val default by lazy { Health() }
  return Health(
    cooldown = overrides[region]?.health?.cooldown ?: defaults.health?.cooldown ?: default.cooldown,
    warmup = overrides[region]?.health?.warmup ?: defaults.health?.warmup ?: default.warmup,
    healthCheckType = overrides[region]?.health?.healthCheckType ?: defaults.health?.healthCheckType
    ?: default.healthCheckType,
    enabledMetrics = overrides[region]?.health?.enabledMetrics ?: defaults.health?.enabledMetrics
    ?: default.enabledMetrics,
    terminationPolicies = overrides[region]?.health?.terminationPolicies
      ?: defaults.health?.terminationPolicies ?: default.terminationPolicies
  )
}

data class ClusterSpec(
  override val moniker: Moniker,
  val imageProvider: ImageProvider? = null,
  val deployWith: ClusterDeployStrategy = RedBlack(),
  override val locations: SubnetAwareLocations,
  private val _defaults: ServerGroupSpec,
  override val overrides: Map<String, ServerGroupSpec> = emptyMap(),
  private val _artifactName: String? = null, // Custom backing field for artifactName, used by resolvers
  override val artifactVersion: String? = null
) : ComputeResourceSpec, Monikered, Locatable<SubnetAwareLocations>, OverrideableClusterDependencyContainer<ServerGroupSpec>, UnhappyControl {
  override val id = "${locations.account}:$moniker"

  /**
   * I have no idea why, but if I annotate the constructor property with @get:JsonUnwrapped, the
   * @JsonCreator constructor below nulls out everything in the ClusterServerGroupSpec some time
   * very late in parsing. Using a debugger I can see it assigning the object correctly but then it
   * seems to overwrite it. This is a bit nasty but I think having the cluster-wide defaults at the
   * top level in the cluster spec YAML / JSON is nicer for the user.
   */
  override val defaults: ServerGroupSpec
    get() = _defaults

  override val artifactType: ArtifactType? = DEBIAN

  // Returns the artifact name set by resolvers, or attempts to find the artifact name from the image provider.
  override val artifactName: String?
    get() = _artifactName
      ?: when (imageProvider) {
        is ArtifactImageProvider -> imageProvider.deliveryArtifact.name
        else -> null
      }

  // Provides a hint as to cluster -> artifact linkage even _without_ resolvers being applied, by delegating to the
  // image provider.
  override val artifactReference: String?
    get() = when (imageProvider) {
      is ReferenceArtifactImageProvider -> imageProvider.reference
      else -> null
    }

  override val maxDiffCount: Int? = 2
  // Once clusters go unhappy, only retry when the diff changes, or if manually unvetoed
  override val unhappyWaitTime: Duration? = null

  data class ServerGroupSpec(
    val launchConfiguration: LaunchConfigurationSpec? = null,
    val capacity: Capacity? = null,
    override val dependencies: ClusterDependencies? = null,
    val health: HealthSpec? = null,
    val scaling: Scaling? = null,
    val tags: Map<String, String>? = null
  ) : ClusterDependencyContainer {
    init {
      // Require capacity.desired or scaling policies, or let them both be blank for constructing overrides
      require(
        capacity == null ||
          (capacity.desired == null && scaling != null && scaling.hasScalingPolicies()) ||
          (capacity.desired != null && (scaling == null || !scaling.hasScalingPolicies())) ||
          (capacity.desired == null && (scaling == null || !scaling.hasScalingPolicies()))
      ) {
        "capacity.desired and auto-scaling policies are mutually exclusive: current = $capacity, $scaling"
      }
    }
  }

  data class HealthSpec(
    val cooldown: Duration? = null,
    val warmup: Duration? = null,
    val healthCheckType: HealthCheckType? = null,
    val enabledMetrics: Set<Metric>? = null,
    val terminationPolicies: Set<TerminationPolicy>? = null
  )
}

operator fun Locations<SubnetAwareRegionSpec>.get(region: String) =
  regions.first { it.name == region }

private operator fun <E> Set<E>?.plus(elements: Set<E>?): Set<E> =
  when {
    this == null || isEmpty() -> elements ?: emptySet()
    elements == null || elements.isEmpty() -> this
    else -> mutableSetOf<E>().also {
      it.addAll(this)
      it.addAll(elements)
    }
  }

private operator fun <K, V> Map<K, V>?.plus(map: Map<K, V>?): Map<K, V> =
  when {
    this == null || isEmpty() -> map ?: emptyMap()
    map == null || map.isEmpty() -> this
    else -> mutableMapOf<K, V>().also {
      it.putAll(this)
      it.putAll(map)
    }
  }
