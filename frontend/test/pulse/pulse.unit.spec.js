import React from 'react'
import { shallow } from 'enzyme'

import UserAvatar from 'metabase/components/UserAvatar'
import RecipientPicker from 'metabase/pulse/components/RecipientPicker'

// We have to do some mocking here to avoid calls to GA and to Metabase settings
jest.mock('metabase/lib/settings', () => ({
    get: () => 'v'
}))

global.ga = jest.fn()

const TEST_USERS = [
    { id: 1, common_name: 'Barb', email: 'barb_holland@hawkins.mail' }, // w
    { id: 2, common_name: 'Dustin', email: 'dustin_henderson@hawkinsav.club' }, // w
    { id: 3, common_name: 'El', email: '011@energy.gov' },
    { id: 4, common_name: 'Lucas', email: 'lucas.sinclair@hawkins.mail' }, // w
    { id: 5, common_name: 'Mike', email: 'dm_mike@hawkins.mail' }, // w
    { id: 6, common_name: 'Nancy', email: '' },
    { id: 7, common_name: 'Steve', email: '' },
    { id: 8, common_name: 'Will', email: 'zombieboy@upside.down' }, // w
]

describe('recipient picker', () => {
        describe('filtering', () => {
            it('should properly filter users based on input', () => {
                const wrapper = shallow(
                    <RecipientPicker
                        recipients={[]}
                        users={TEST_USERS}
                        isNewPulse={true}
                        onRecipientsChange={() => alert('why?')}
                    />
                )

                const spy = jest.spyOn(wrapper.instance(), 'setInputValue')
                const input = wrapper.find('input')

                // we should start off with no users
                expect(wrapper.state().filteredUsers.length).toBe(0)

                // simulate typing 'w'
                input.simulate('change', { target: { value: 'w' }})

                expect(spy).toHaveBeenCalled()
                expect(wrapper.state().inputValue).toEqual('w')

                // 5 of the test users have a w in their name or email
                expect(wrapper.state().filteredUsers.length).toBe(5)
            })
        })

        describe('recipient selection', () => {
            it('should allow the user to click to select a recipient', () => {
                const spy = jest.fn()
                const wrapper = shallow(
                    <RecipientPicker
                        recipients={[]}
                        users={TEST_USERS}
                        isNewPulse={true}
                        onRecipientsChange={spy}
                    />
                )

                const input = wrapper.find('input')
                input.simulate('change', { target: { value: 'steve' }})

                expect(wrapper.state().filteredUsers.length).toBe(1)

                const user = wrapper.find(UserAvatar).closest('li')
                user.simulate('click', { target: {}})

                expect(spy).toHaveBeenCalled()
            })
        })
})
