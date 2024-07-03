package org.apache.dubbo.demo.consumer.invoker.listener;

import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.common.extension.Activate;
import org.apache.dubbo.rpc.Invoker;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.listener.InvokerListenerAdapter;

/**
 * @author Runner
 * @version 1.0
 * @date 2024/7/3 9:24
 * @description
 */
@Activate(group = CommonConstants.CONSUMER, value = "invoker.listener")
public class MyInvokerListener extends InvokerListenerAdapter {
    @Override
    public void referred(Invoker<?> invoker) throws RpcException {
        System.out.println("refer invoker url is + " + invoker.getUrl().toString());
    }

}
