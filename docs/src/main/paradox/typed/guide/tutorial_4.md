# Part 4: Working with Device Groups

## Introduction

Let's take a closer look at the main functionality required by our use case. In a complete IoT system for monitoring home temperatures, the steps for connecting a device sensor to our system might look like this:

1. A sensor device in the home connects through some protocol.
1. The component managing network connections accepts the connection.
1. The sensor provides its group and device ID to register with the device manager component of our system.
1. The device manager component handles registration by looking up or creating the actor responsible for keeping sensor state.
1. The actor responds with an acknowledgement, exposing its @apidoc[typed.ActorRef].
1. The networking component now uses the `ActorRef` for communication between the sensor and device actor without going through the device manager.

Steps 1 and 2 take place outside the boundaries of our tutorial system. In this chapter, we will start addressing steps 3-6 and create a way for sensors to register with our system and to communicate with actors. But first, we have another architectural decision &#8212; how many levels of actors should we use to represent device groups and device sensors?

One of the main design challenges for Pekko programmers is choosing the best granularity for actors. In practice, depending on the characteristics of the interactions between actors, there are usually several valid ways to organize a system. In our use case, for example, it would be possible to have a single actor maintain all the groups and devices  &#8212; perhaps using hash maps. It would also be reasonable to have an actor for each group that tracks the state of all devices in the same home.

The following guidelines help us choose the most appropriate actor hierarchy:

  * In general, prefer larger granularity. Introducing more fine-grained actors than needed causes more problems than it solves.
  * Add finer granularity when the system requires:
      * Higher concurrency.
      * Complex conversations between actors that have many
    states. We will see a very good example for this in the next chapter.
      * Sufficient state that it makes sense to divide into smaller
    actors.
      * Multiple unrelated responsibilities. Using separate actors allows individuals to fail and be restored with little impact on others.

## Device manager hierarchy

Considering the principles outlined in the previous section, We will model the device manager component as an actor tree with three levels:

* The top level supervisor actor represents the system component for devices. It is also the entry point to look up and create device group and device actors.
* At the next level, group actors each supervise the device actors for one group id (e.g. one home). They also provide services, such as querying temperature readings from all of the available devices in their group.
* Device actors manage all the interactions with the actual device sensors, such as storing temperature readings.

![device manager tree](diagrams/device_manager_tree.png)

We chose this three-layered architecture for these reasons:

* Having groups of individual actors:
    * Isolates failures that occur in a group. If a single actor managed all device groups, an error in one group that causes a restart would wipe out the state of groups that are otherwise non-faulty.
    * Simplifies the problem of querying all the devices belonging to a group. Each group actor only contains state related to its group.
    * Increases parallelism in the system. Since each group has a dedicated actor, they run concurrently and we can query multiple groups concurrently.


* Having sensors modeled as individual device actors:
    * Isolates failures of one device actor from the rest of the devices in the group.
    * Increases the parallelism of collecting temperature readings. Network connections from different sensors communicate with their individual device actors directly, reducing contention points.

With the architecture defined, we can start working on the protocol for registering sensors.

## The Registration Protocol

As the first step, we need to design the protocol both for registering a device and for creating the group and device actors that will be responsible for it. This protocol will be provided by the `DeviceManager` component itself because that is the only actor that is known and available up front: device groups and device actors are created on-demand.

Looking at registration in more detail, we can outline the necessary functionality:

1. When a `DeviceManager` receives a request with a group and device id:
    * If the manager already has an actor for the device group, it forwards the request to it.
    * Otherwise, it creates a new device group actor and then forwards the request.
1. The `DeviceGroup` actor receives the request to register an actor for the given device:
    * If the group already has an actor for the device it replies with the @apidoc[typed.ActorRef] of the existing device actor.
    * Otherwise, the `DeviceGroup` actor first creates a device actor and replies with the `ActorRef` of the newly created device actor.
1. The sensor will now have the `ActorRef` of the device actor to send messages directly to it.

The messages that we will use to communicate registration requests and their acknowledgement have the definition:

Scala
:   @@snip [DeviceManager.scala](/docs/src/test/scala/typed/tutorial_4/DeviceManager.scala) { #device-registration-msgs }

Java
:   @@snip [DeviceManager.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceManager.java) { #device-registration-msgs }

In this case we have not included a request ID field in the messages. Since registration happens once, when the component connects the system to some network protocol, the ID is not important. However, it is usually a best practice to include a request ID.

Now, we'll start implementing the protocol from the bottom up. In practice, both a top-down and bottom-up approach can work, but in our case, we benefit from the bottom-up approach as it allows us to immediately write tests for the new features without mocking out parts that we will need to build later.

## Adding registration support to device group actors

A group actor has some work to do when it comes to registrations, including:

* Handling the registration request for existing device actor or by creating a new actor.
* Tracking which device actors exist in the group and removing them from the group when they are stopped.

### Handling the registration request

A device group actor must either reply to the request with the @apidoc[typed.ActorRef] of an existing child, or it should create one. To look up child actors by their device IDs we will use a `Map`.

Add the following to your source file:

Scala
:   @@snip [DeviceGroup.scala](/docs/src/test/scala/typed/tutorial_4/DeviceGroup.scala) { #device-group-register }

Java
:   @@snip [DeviceGroup.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceGroup.java) { #device-group-register }

Just as we did with the device, we test this new functionality. We also test that the actors returned for the two different IDs are actually different, and we also attempt to record a temperature reading for each of the devices to see if the actors are responding.

Scala
:   @@snip [DeviceGroupSpec.scala](/docs/src/test/scala/typed/tutorial_4/DeviceGroupSpec.scala) { #device-group-test-registration }

Java
:   @@snip [DeviceGroupTest.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceGroupTest.java) { #device-group-test-registration }

If a device actor already exists for the registration request, we would like to use
the existing actor instead of a new one. We have not tested this yet, so we need to fix this:

Scala
:   @@snip [DeviceGroupSpec.scala](/docs/src/test/scala/typed/tutorial_4/DeviceGroupSpec.scala) { #device-group-test3 }

Java
:   @@snip [DeviceGroupTest.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceGroupTest.java) { #device-group-test3 }


### Keeping track of the device actors in the group

So far, we have implemented logic for registering device actors in the group. Devices come and go, however, so we will need a way to remove device actors from the @scala[`Map[String, ActorRef[DeviceMessage]]`]@java[`Map<String, ActorRef<DeviceMessage>>`]. We will assume that when a device is removed, its corresponding device actor is stopped. Supervision, as we discussed earlier, only handles error scenarios &#8212; not graceful stopping. So we need to notify the parent when one of the device actors is stopped.

Pekko provides a _Death Watch_ feature that allows an actor to _watch_ another actor and be notified if the other actor is stopped. Unlike supervision, watching is not limited to parent-child relationships, any actor can watch any other actor as long as it knows the @apidoc[typed.ActorRef]. After a watched actor stops, the watcher receives a @apidoc[Terminated(actorRef)](typed.Terminated) signal which also contains the reference to the watched actor. The watcher can either handle this message explicitly or will fail with a @apidoc[typed.DeathPactException]. This latter is useful if the actor can no longer perform its own duties after the watched actor has been stopped. In our case, the group should still function after one device have been stopped, so we need to handle the `Terminated(actorRef)` signal.

Our device group actor needs to include functionality that:

 1. Starts watching new device actors when they are created.
 2. Removes a device actor from the @scala[`Map[String, ActorRef[DeviceMessage]]`]@java[`Map<String, ActorRef<DeviceMessage>>`] &#8212; which maps devices to device actors &#8212; when the notification indicates it has stopped.

Unfortunately, the `Terminated` signal only contains the `ActorRef` of the child actor. We need the actor's ID to remove it from the map of existing device to device actor mappings. An alternative to the `Terminated` signal is to define a custom message that will be sent when the watched actor is stopped. We will use that here because it gives us the possibility to carry the device ID in that message.


Adding the functionality to identify the actor results in this:

Scala
:   @@snip [DeviceGroup.scala](/docs/src/test/scala/typed/tutorial_4/DeviceGroup.scala) { #device-group-remove }

Java
:   @@snip [DeviceGroup.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceGroup.java) { #device-group-remove }

So far we have no means to get which devices the group device actor keeps track of and, therefore, we cannot test our new functionality yet. To make it testable, we add a new query capability (message `RequestDeviceList`) that lists the currently active device IDs:

Scala
:   @@snip [DeviceManager.scala](/docs/src/test/scala/typed/tutorial_4/DeviceManager.scala) { #device-list-msgs }

Java
:   @@snip [DeviceManager.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceManager.java) { #device-list-msgs }


Scala
:   @@snip [DeviceGroup.scala](/docs/src/test/scala/typed/tutorial_4/DeviceGroup.scala) { #device-group-full }

Java
:   @@snip [DeviceGroup.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceGroup.java) { #device-group-full }

We are almost ready to test the removal of devices. But, we still need the following capabilities:

 * To stop a device actor from our test case, from the outside, we must send a message to it. We add a `Passivate` message which instructs the actor to stop.
 * To be notified once the device actor is stopped. We can use the _Death Watch_ facility for this purpose, too.

Scala
:   @@snip [Device.scala](/docs/src/test/scala/typed/tutorial_4/Device.scala) { #passivate-msg }

Java
:   @@snip [Device.java](/docs/src/test/java/jdocs/typed/tutorial_4/Device.java) { #passivate-msg }


Scala
:   @@snip [Device.scala](/docs/src/test/scala/typed/tutorial_4/Device.scala) { #device-with-passivate }

Java
:   @@snip [Device.java](/docs/src/test/java/jdocs/typed/tutorial_4/Device.java) { #device-with-passivate }


We add two more test cases now. In the first, we test that we get back the list of proper IDs once we have added a few devices. The second test case makes sure that the device ID is properly removed after the device actor has been stopped.  The @apidoc[typed.*.TestProbe] has a `expectTerminated` method that we can easily use to assert that the device actor has been terminated.

Scala
:   @@snip [DeviceGroupSpec.scala](/docs/src/test/scala/typed/tutorial_4/DeviceGroupSpec.scala) { #device-group-list-terminate-test }

Java
:   @@snip [DeviceGroupTest.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceGroupTest.java) { #device-group-list-terminate-test }

## Creating device manager actors

Going up to the next level in our hierarchy, we need to create the entry point for our device manager component in the `DeviceManager` source file. This actor is very similar to the device group actor, but creates device group actors instead of device actors:

Scala
:   @@snip [DeviceManager.scala](/docs/src/test/scala/typed/tutorial_4/DeviceManager.scala) { #device-manager-full }

Java
:   @@snip [DeviceManager.java](/docs/src/test/java/jdocs/typed/tutorial_4/DeviceManager.java) { #device-manager-full }

We leave tests of the device manager as an exercise for you since it is very similar to the tests we have already written for the group
actor.

## What's next?

We have now a hierarchical component for registering and tracking devices and recording measurements. We have seen how to implement different types of conversation patterns, such as:

 * Request-respond (for temperature recordings)
 * Create-on-demand (for registration of devices)
 * Create-watch-terminate (for creating the group and device actor as children)

In the next chapter, we will introduce group query capabilities, which will establish a new conversation pattern of scatter-gather. In particular, we will implement the functionality that allows users to query the status of all the devices belonging to a group.
