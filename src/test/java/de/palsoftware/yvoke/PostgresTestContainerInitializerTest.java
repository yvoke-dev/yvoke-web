package de.palsoftware.yvoke;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.api.model.Ports.Binding;
import java.util.List;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;
import org.testcontainers.core.CreateContainerCmdModifier;

class PostgresTestContainerInitializerTest {

    @Test
    void serviceLoaderRegistersInitializerAsCreateContainerCmdModifierForAllTestcontainers() {
        List<CreateContainerCmdModifier> modifiers =
            ServiceLoader.load(CreateContainerCmdModifier.class).stream()
                .map(ServiceLoader.Provider::get).toList();

        assertThat(modifiers)
            .as("Testcontainers creates RyukContainer internally via ServiceLoader-discovered "
                + "CreateContainerCmdModifiers; without this registration Ryuk and any container "
                + "without an explicit modifier still bind [::]:0 on macOS")
            .anyMatch(PostgresTestContainerInitializer.class::isInstance);
    }

    @Test
    void modifyRewritesWildcardPortBindingsToIpv4LoopbackAndPreservesPortSpec() {
        HostConfig hostConfig = new HostConfig()
            .withPortBindings(List.of(new PortBinding(Binding.empty(), ExposedPort.tcp(5432)),
                new PortBinding(Binding.bindPort(15432), ExposedPort.tcp(8080)),
                new PortBinding(Binding.bindIpAndPort("10.0.0.1", 9000), ExposedPort.tcp(9000))));
        CreateContainerCmd cmd = mock(CreateContainerCmd.class);
        when(cmd.getHostConfig()).thenReturn(hostConfig);

        PostgresTestContainerInitializer initializer = new PostgresTestContainerInitializer();
        assertThat((Object) initializer).isInstanceOf(CreateContainerCmdModifier.class);
        ((CreateContainerCmdModifier) initializer).modify(cmd);

        Ports ports = hostConfig.getPortBindings();
        assertThat(ports.getBindings().get(ExposedPort.tcp(5432)))
            .containsExactly(new Binding("127.0.0.1", null));
        assertThat(ports.getBindings().get(ExposedPort.tcp(8080)))
            .containsExactly(new Binding("127.0.0.1", "15432"));
        assertThat(ports.getBindings().get(ExposedPort.tcp(9000)))
            .containsExactly(new Binding("10.0.0.1", "9000"));
    }

    @Test
    void isRemoteTcpDockerHost_recognizesLocalAndRemoteHosts() {
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost(null)).isFalse();
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost("")).isFalse();
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost("   ")).isFalse();
        assertThat(
            PostgresTestContainerInitializer.isRemoteTcpDockerHost("unix:///var/run/docker.sock"))
            .isFalse();
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost("tcp://localhost:2375"))
            .isFalse();
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost("TCP://LOCALHOST:2375"))
            .isFalse();
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost("tcp://127.0.0.1:2375"))
            .isFalse();
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost("tcp://[::1]:2375"))
            .isFalse();
        assertThat(PostgresTestContainerInitializer.isRemoteTcpDockerHost("tcp://10.0.0.5:2375"))
            .isTrue();
        assertThat(
            PostgresTestContainerInitializer.isRemoteTcpDockerHost("tcp://docker.example.com:2376"))
            .isTrue();
        assertThat(
            PostgresTestContainerInitializer.isRemoteTcpDockerHost("ssh://user@remote-docker-host"))
            .isTrue();
        assertThat(PostgresTestContainerInitializer
            .isRemoteTcpDockerHost("SSH://user@remote-docker-host:22")).isTrue();
    }

    @Test
    void modifyLeavesWildcardPortBindingsUntouchedWhenDockerHostPropertyIsRemoteSsh() {
        String previous = System.getProperty("docker.host");
        try {
            System.setProperty("docker.host", "ssh://user@remote-docker-host");
            HostConfig hostConfig = new HostConfig()
                .withPortBindings(List.of(new PortBinding(Binding.empty(), ExposedPort.tcp(5432))));
            CreateContainerCmd cmd = mock(CreateContainerCmd.class);
            when(cmd.getHostConfig()).thenReturn(hostConfig);

            new PostgresTestContainerInitializer().modify(cmd);

            Ports ports = hostConfig.getPortBindings();
            assertThat(ports.getBindings().get(ExposedPort.tcp(5432)))
                .containsExactly(Binding.empty());
        } finally {
            if (previous == null) {
                System.clearProperty("docker.host");
            } else {
                System.setProperty("docker.host", previous);
            }
        }
    }
}
