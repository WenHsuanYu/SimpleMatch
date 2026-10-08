#!/usr/bin/env ruby
require "json"
require "minitest/autorun"
require_relative "../lib/matching-owner-observation"

class MatchingOwnerObservationTest < Minitest::Test
  def resources
    JSON.parse(File.read(File.join(__dir__, "fixtures/matching-owner-resources.json")))
  end

  def test_redacts_actual_pod_storage_and_image_identity
    owner = MatchingOwnerObservation.owner(resources)
    expected = JSON.parse(File.read(File.join(__dir__, "fixtures/matching-recovery.json"))).fetch("beforeOwner")
    assert_equal expected, owner
    refute owner.key?("spec")
  end

  def test_storage_mismatch_or_unmounted_owner_fail_closed
    {
      ["pv", "spec", "claimRef", "uid"] => "unrelated-claim",
      ["pod", "spec", "nodeName"] => "another-node",
      ["pvc", "status", "phase"] => "Pending"
    }.each do |path, value|
      observation = resources
      observation.dig(*path[0...-1])[path.last] = value
      assert_raises(RestingBuyVerification::InvalidEvidence) { MatchingOwnerObservation.owner(observation) }
    end
    observation = resources
    observation.fetch("pod").fetch("spec")["volumes"] = []
    assert_raises(RestingBuyVerification::InvalidEvidence) { MatchingOwnerObservation.owner(observation) }
  end

  def test_deletion_is_observed_for_the_exact_original_uid_not_inferred_from_ready
    original = resources.fetch("pod")
    refute MatchingOwnerObservation.interruption({"items" => [original]}, "matching-original-uid").fetch("oldOwnerInterrupted")
    original.fetch("metadata")["deletionTimestamp"] = "2026-08-27T16:00:00Z"
    refute MatchingOwnerObservation.interruption({"items" => [original]}, "matching-original-uid").fetch("oldOwnerInterrupted")
    original.fetch("status").fetch("containerStatuses").first["state"] = {"terminated" => {}}
    assert MatchingOwnerObservation.interruption({"items" => [original]}, "matching-original-uid").fetch("oldOwnerInterrupted")
    absent = MatchingOwnerObservation.interruption({"items" => []}, "matching-original-uid")
    assert absent.fetch("oldOwnerInterrupted")
    assert_equal "matching-original-uid", absent.fetch("originalPodUid")
  end
end
