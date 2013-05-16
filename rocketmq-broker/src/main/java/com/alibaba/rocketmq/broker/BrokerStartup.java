/**
 * $Id: BrokerStartup.java 1839 2013-05-16 02:12:02Z shijia.wxr $
 */
package com.alibaba.rocketmq.broker;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.PosixParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;

import com.alibaba.rocketmq.common.BrokerConfig;
import com.alibaba.rocketmq.common.MetaMix;
import com.alibaba.rocketmq.common.MetaVersion;
import com.alibaba.rocketmq.remoting.netty.NettyServerConfig;
import com.alibaba.rocketmq.remoting.protocol.RemotingCommand;
import com.alibaba.rocketmq.store.config.MessageStoreConfig;


/**
 * Broker�������
 * 
 * @author vintage.wang@gmail.com shijia.wxr@taobao.com
 */
public class BrokerStartup {

    public static Options buildCommandlineOptions(final Options options) {
        Option opt = new Option("c", "configFile", true, "Broker config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("t", "topicProperties", true, "Topic config properties file");
        opt.setRequired(false);
        options.addOption(opt);

        opt = new Option("p", "printConfigItem", false, "Print all config item");
        opt.setRequired(false);
        options.addOption(opt);

        return options;
    }


    public static void main(String[] args) {
        // ���õ�ǰ����汾�ţ�ÿ�η����汾ʱ����Ҫ�޸�CurrentVersion
        System.setProperty(RemotingCommand.RemotingVersionKey, Integer.toString(MetaVersion.CurrentVersion));

        try {
            // ����������
            Options options = MetaMix.buildCommandlineOptions(new Options());
            final CommandLine commandLine =
                    MetaMix.parseCmdLine("mqbroker", args, buildCommandlineOptions(options), new PosixParser());
            if (null == commandLine) {
                System.exit(-1);
                return;
            }

            // ��ʼ�������ļ�
            final BrokerConfig brokerConfig = new BrokerConfig();
            final NettyServerConfig nettyServerConfig = new NettyServerConfig();
            nettyServerConfig.setListenPort(10911);
            final MessageStoreConfig messageStoreConfig = new MessageStoreConfig();

            // ��ӡĬ������
            if (commandLine.hasOption('p')) {
                MetaMix.printObjectProperties(null, brokerConfig);
                MetaMix.printObjectProperties(null, nettyServerConfig);
                MetaMix.printObjectProperties(null, messageStoreConfig);
                System.exit(0);
            }

            // ָ�������ļ�
            if (commandLine.hasOption('c')) {
                String file = commandLine.getOptionValue('c');
                if (file != null) {
                    InputStream in = new BufferedInputStream(new FileInputStream(file));
                    Properties properties = new Properties();
                    properties.load(in);
                    MetaMix.properties2Object(properties, brokerConfig);
                    MetaMix.properties2Object(properties, nettyServerConfig);
                    MetaMix.properties2Object(properties, messageStoreConfig);

                    brokerConfig.setConfigFilePath(file);

                    System.out.println("load config properties file OK, " + file);
                }
            }

            // ָ��Topic������
            if (commandLine.hasOption('t')) {
                String file = commandLine.getOptionValue('t');
                if (file != null) {
                    brokerConfig.setTopicConfigPath(file);
                }
            }

            MetaMix.properties2Object(MetaMix.commandLine2Properties(commandLine), brokerConfig);

            if (null == brokerConfig.getRocketmqHome()) {
                System.out.println("Please set the " + MetaMix.ROCKETMQ_HOME_ENV
                        + " variable in your environment to match the location of the Metaq installation");
                System.exit(-2);
            }

            // BrokerId�Ĵ���
            switch (messageStoreConfig.getBrokerRole()) {
            case ASYNC_MASTER:
            case SYNC_MASTER:
                // Master Id������0
                brokerConfig.setBrokerId(MetaMix.MASTER_ID);
                break;
            case SLAVE:
                // Slave Id��Slave����IP���˿ھ���
                long id = MetaMix.createBrokerId(brokerConfig.getBrokerIP1(), nettyServerConfig.getListenPort());
                brokerConfig.setBrokerId(id);
                break;
            default:
                break;
            }

            // Master����Slave����Ķ˿ڣ�Ĭ��Ϊ����˿�+1
            messageStoreConfig.setHaListenPort(nettyServerConfig.getListenPort() + 1);

            // ������־
            /*
             * try { // assume SLF4J is bound to logback in the current
             * environment LoggerContext context = (LoggerContext)
             * LoggerFactory.getILoggerFactory(); JoranConfigurator configurator
             * = new JoranConfigurator(); configurator.setContext(context);
             * context.reset();
             * configurator.doConfigure(brokerConfig.getMetaqHome() +
             * "/conf/log4j_broker.xml");
             * StatusPrinter.printInCaseOfErrorsOrWarnings(context); } catch
             * (JoranException je) { }
             * 
             * final Logger log =
             * LoggerFactory.getLogger(MetaMix.BrokerLoggerName);
             * 
             * final Logger log = null;
             */

            // ��ʼ��Logback
            LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
            JoranConfigurator configurator = new JoranConfigurator();
            configurator.setContext(lc);
            lc.reset();
            configurator.doConfigure(brokerConfig.getRocketmqHome() + "/conf/log4j_broker.xml");
            final Logger log = LoggerFactory.getLogger(MetaMix.BrokerLoggerName);

            // ��ӡ��������
            MetaMix.printObjectProperties(log, brokerConfig);
            MetaMix.printObjectProperties(log, nettyServerConfig);
            MetaMix.printObjectProperties(log, messageStoreConfig);

            // ��ʼ��������ƶ���
            final BrokerController controller =
                    new BrokerController(brokerConfig, nettyServerConfig, messageStoreConfig);
            boolean initResult = controller.initialize();
            if (!initResult) {
                controller.shutdown();
                System.exit(-3);
            }

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                private volatile boolean hasShutdown = false;
                private AtomicInteger shutdownTimes = new AtomicInteger(0);


                @Override
                public void run() {
                    synchronized (this) {
                        log.info("shutdown hook was invoked, " + this.shutdownTimes.incrementAndGet());
                        if (!this.hasShutdown) {
                            this.hasShutdown = true;
                            long begineTime = System.currentTimeMillis();
                            controller.shutdown();
                            long consumingTimeTotal = System.currentTimeMillis() - begineTime;
                            log.info("shutdown hook over, consuming time total(ms): " + consumingTimeTotal);
                        }
                    }
                }
            }, "ShutdownHook"));

            // ����������ƶ���
            controller.start();
            String tip =
                    "The broker[" + controller.getBrokerConfig().getBrokerName() + ", "
                            + controller.getBrokerAddr() + "] boot success.";
            log.info(tip);
            System.out.println(tip);
        }
        catch (Throwable e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }
}