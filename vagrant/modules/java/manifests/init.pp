class java {
  require java::base

  File["/root/java-accept-lic"] ~> Exec["accept-java-lic"] ~> Exec["add-java-home"] -> Package["sun-java6-jdk"]
  
  File { 
    owner => "root", 
    group => "root",
    mode => 0440,
  }
  
  file { "/root/java-accept-lic":
    source => "puppet:///modules/java/root/java-accept-lic", 
  }
  
  exec { "accept-java-lic":
    command => "/usr/bin/debconf-set-selections /root/java-accept-lic",
    refreshonly => true,
  }

  exec { "add-java-home":
    command => "/bin/bash -c 'echo JAVA_HOME=/usr/lib/jvm/java-6-sun-1.6.0.21' >> /etc/environment",
    refreshonly => true
  }
  
  package { "sun-java6-jdk":
    ensure  => "present"
  }
}

class java::base {
    require java::repository
}

class java::repository {
    apt::source { "java-repository":
        location => "http://ppa.launchpad.net/sun-java-community-team/sun-java6/ubuntu",
        release => "lucid",
        repos => "main",
        key => "3EBCE749"
    }
}