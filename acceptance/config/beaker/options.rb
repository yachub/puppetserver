{:forge_host=>"forge-aio01-petest.puppetlabs.com",
 :is_puppetserver=>true,
 :is_jvm_puppet=>true,
 "service-wait"=>true,
 "service-prefix"=>"service ",
 "service-num-retries"=>1500,
 "puppetservice"=>"puppetserver",
 "puppetserver-package"=>"puppetserver",
 "use-service"=>true,
 "master-start-curl-retries"=>60,
 "puppetserver-confdir"=>"/etc/puppetlabs/puppetserver/conf.d",
 "puppetserver-config"=>
  "/etc/puppetlabs/puppetserver/conf.d/puppetserver.conf",
 :puppet_build_version=>"8c9e321f95ad0b52fbf4373b1cb9151d4c1edb18"}
