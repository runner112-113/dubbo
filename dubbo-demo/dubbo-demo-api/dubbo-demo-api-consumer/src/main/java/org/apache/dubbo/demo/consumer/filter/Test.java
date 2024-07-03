package org.apache.dubbo.demo.consumer.filter;

public class Test {

    public static void main(String[] args) {
        ListNode list1 = new ListNode(-9, new ListNode(3, null));
        ListNode list2 = new ListNode(5,  new ListNode(7, null));
        ListNode listNode = mergeTwoLists(list1, list2);
        while (listNode !=null) {
            System.out.println(listNode.val);
            listNode = listNode.next;
        }
    }

    static class ListNode {
        int val;
        ListNode next;
        ListNode() {}
        ListNode(int val) { this.val = val; }
        public ListNode(int val, ListNode next) { this.val = val; this.next = next; }
 }

    public static ListNode mergeTwoLists(ListNode list1, ListNode list2) {
        ListNode curNode;
        ListNode nextCalNode = list2;
        if (list1 == null) return list2;
        if (list2 == null) return list1;
        while(nextCalNode != null) {
            curNode = nextCalNode;
            if(curNode.val <= list1.val) {
                // 下一个迭代的
                nextCalNode = curNode.next;
                curNode.next = list1;
                list1 = curNode;
                continue;
            }

            ListNode nextCalNode1 = list1;
            while(nextCalNode1 != null) {
                if(nextCalNode1.next == null) {
                    nextCalNode = curNode.next;
                    curNode.next = null;
                    nextCalNode1.next = curNode;
                    break;
                }
                else if(curNode.val >= nextCalNode1.val && curNode.val <= nextCalNode1.next.val) {
                    ListNode temp = nextCalNode1.next;
                    nextCalNode1.next = curNode;
                    nextCalNode = curNode.next;
                    curNode.next = temp;
                    break;
                }else {
                    nextCalNode1 = nextCalNode1.next;
                }
            }

        }

        return list1;

    }
}
