#!/usr/bin/env ruby
require_relative "matching-recovery-verification"

# Redacts actual Kubernetes observations, keeping raw resources in private scratch space.
module MatchingOwnerObservation
  def self.owner(resources)
    pod, claim, volume = resources.values_at("pod", "pvc", "pv")
    container = pod.fetch("spec").fetch("containers").find { |item| item["name"] == "matching" }
    mount = pod.fetch("spec").fetch("volumes").find { |item| item["name"] == "matching-baseline" }
    MatchingRecoveryVerification.equal(claim.fetch("metadata").fetch("name"), mount.fetch("persistentVolumeClaim").fetch("claimName"), "mounted claim")
    data_mount = container.fetch("volumeMounts").find { |item| item["name"] == "matching-baseline" }
    MatchingRecoveryVerification.equal("/var/lib/simplematch/matching", data_mount.fetch("mountPath"), "baseline mount")
    MatchingRecoveryVerification.equal(volume.fetch("metadata").fetch("name"), claim.fetch("spec").fetch("volumeName"), "bound volume")
    MatchingRecoveryVerification.equal(claim.fetch("metadata").fetch("uid"), volume.fetch("spec").fetch("claimRef").fetch("uid"), "volume claim UID")
    [claim, volume].each { |item| MatchingRecoveryVerification.equal("Bound", item.fetch("status").fetch("phase"), "storage binding") }
    node = pod.fetch("spec").fetch("nodeName")
    terms = volume.fetch("spec").fetch("nodeAffinity").fetch("required").fetch("nodeSelectorTerms")
    MatchingRecoveryVerification.equal(true, terms.any? { |term| term.fetch("matchExpressions").any? { |expression|
      expression["key"] == "kubernetes.io/hostname" && expression["operator"] == "In" && expression.fetch("values").include?(node)
    } }, "volume node assignment")
    status = pod.fetch("status")
    image = status.fetch("containerStatuses").find { |item| item["name"] == "matching" }
    {
      "podName" => pod.fetch("metadata").fetch("name"), "podUid" => pod.fetch("metadata").fetch("uid"),
      "nodeName" => node, "pvcName" => claim.fetch("metadata").fetch("name"), "pvcUid" => claim.fetch("metadata").fetch("uid"),
      "pvName" => volume.fetch("metadata").fetch("name"), "pvUid" => volume.fetch("metadata").fetch("uid"),
      "imageId" => image.fetch("imageID"),
      "ready" => status.fetch("conditions").any? { |item| item["type"] == "Ready" && item["status"] == "True" }
    }
  rescue KeyError, NoMethodError, TypeError => failure
    raise RestingBuyVerification::InvalidEvidence, "invalid Matching owner resources: #{failure.class}"
  end

  def self.interruption(pods, original_uid)
    original = pods.fetch("items").find { |pod| pod.fetch("metadata").fetch("uid") == original_uid }
    stopped = original.nil? ||
      original.fetch("status").fetch("containerStatuses", []).none? { |container|
        container["name"] == "matching" && container.fetch("state").key?("running")
      }
    {"originalPodUid" => original_uid, "oldOwnerInterrupted" => stopped, "observedAtEpochMs" => (Time.now.to_r * 1000).to_i}
  end
end

if $PROGRAM_NAME == __FILE__
  operation, *paths = ARGV
  begin
    document = case operation
    when "owner"
      MatchingOwnerObservation.owner(%w[pod pvc pv].zip(paths).to_h.transform_values { |path| JSON.parse(File.read(path)) })
    when "interruption"
      MatchingOwnerObservation.interruption(JSON.parse(STDIN.read), paths.fetch(0))
    when "timing"
      {"startedAtEpochMs" => Integer(paths.fetch(0)), "recoveredAtEpochMs" => Integer(paths.fetch(1)),
        "completedAtEpochMs" => (Time.now.to_r * 1000).to_i, "budgetMillis" => Integer(paths.fetch(2))}
    else
      abort "usage: matching-owner-observation.rb owner POD PVC PV | interruption UID | timing START RECOVERED BUDGET"
    end
    puts JSON.pretty_generate(document)
  rescue RestingBuyVerification::InvalidEvidence, KeyError, JSON::ParserError => failure
    warn failure.message
    exit 1
  end
end
