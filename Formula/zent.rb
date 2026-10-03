# The tap is this repo: `brew tap olivergg/zent https://github.com/olivergg/zent`.
# HEAD only until a release is tagged (then: url + sha256 of its tarball).
class Zent < Formula
  desc "Local dev stacks (compose, processes, Quarkus apps, k8s) from an EDN catalog"
  homepage "https://github.com/olivergg/zent"
  license "Apache-2.0"
  head "https://github.com/olivergg/zent.git", branch: "main"

  depends_on "jolt-lang/jolt/jolt"

  def install
    # sources, not a build: jolt runs them as is (a jolt binary embeds no
    # resources, so the dashboard and reload-code would be lost)
    libexec.install %w[bin deps.edn resources src]
    # what `zent version` prints - HEAD-<sha> for a --HEAD install
    (libexec/"VERSION").write "#{version}\n"
    bin.install_symlink libexec/"bin/zent"
  end

  def caveats
    <<~EOS
      After `brew upgrade zent`, move a running daemon onto the new version
      (what it runs keeps running):
        zent shutdown && zent serve --detach
    EOS
  end

  test do
    (testpath/"catalog.edn").write "{:name :t :components {:db {:kind :external}}}"
    (testpath/"presets").mkpath
    (testpath/"presets/p.edn").write "[:db]"
    assert_match "1 preset(s) ok", shell_output("#{bin}/zent check")
    assert_match "zent #{version}", shell_output("#{bin}/zent version")
  end
end
